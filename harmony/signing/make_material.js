/*
 * Build a project-local hvigor "material" key directory and encrypt passwords
 * with the exact algorithm hvigor's DecipherUtil uses to decrypt them:
 *   key    = AES128-GCM(workKey) encrypted under rootKey
 *   rootKey= PBKDF2( (fd0^fd1^fd2^component).toString(), salt, 10000, 16, sha256 )
 *   pwd    = AES128-GCM(workKey, plain) serialized as be32(ctLen+16)||iv||ct||tag
 * Layout required by DecipherUtil.getKey (relative to storeFile dir):
 *   material/fd/<d>/<f> x3 (16 bytes each), material/ac/<f> (salt), material/ce/<f>
 */
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const component = [49, 243, 9, 115, 214, 175, 91, 184, 211, 190, 177, 88, 101, 131, 192, 119];

function gcmEncrypt(key, plaintext) {
    const iv = crypto.randomBytes(12);
    const c = crypto.createCipheriv('aes-128-gcm', key, iv);
    const ct = Buffer.concat([c.update(plaintext), c.final()]);
    const tag = c.getAuthTag();
    const hdr = Buffer.alloc(4);
    hdr.writeUInt32BE(ct.length + 16); // be32 value hvigor reads as len-4-ivLen
    return Buffer.concat([hdr, iv, ct, tag]);
}

const mat = path.join(__dirname, 'material');
fs.rmSync(mat, { recursive: true, force: true });
for (const d of ['fd/0', 'fd/1', 'fd/2', 'ac', 'ce']) {
    fs.mkdirSync(path.join(mat, d), { recursive: true });
}

const fd = [crypto.randomBytes(16), crypto.randomBytes(16), crypto.randomBytes(16)];
fd.forEach((b, i) => fs.writeFileSync(path.join(mat, 'fd', String(i), 'k'), b));
const salt = crypto.randomBytes(16);
fs.writeFileSync(path.join(mat, 'ac', 'k'), salt);

// xorComponents returns Buffer.from(xored); its .toString() is UTF-8 (Node default)
const xored = Buffer.alloc(16);
for (let i = 0; i < 16; i++) {
    xored[i] = (fd[0][i] ^ fd[1][i] ^ fd[2][i] ^ component[i]) & 0xff;
}
const rootKey = crypto.pbkdf2Sync(xored.toString('utf8'), salt, 10000, 16, 'sha256');

const workKey = crypto.randomBytes(16);
fs.writeFileSync(path.join(mat, 'ce', 'k'), gcmEncrypt(rootKey, workKey));

const plain = process.argv[2] || process.env.LIBRERA_SIGN_PW || 'LibreraDebug';
const encPwd = gcmEncrypt(workKey, Buffer.from(plain, 'utf8')).toString('hex').toUpperCase();
fs.writeFileSync(path.join(__dirname, 'enc_store.txt'), encPwd);
fs.writeFileSync(path.join(__dirname, 'enc_key.txt'), encPwd);

// sanity: re-run hvigor's exact decrypt path on our own output
function gcmDecrypt(key, r) {
    const e = r.readUInt32BE(0);
    const i = r.length - 4 - e; // ivLen
    const iv = r.slice(4, 4 + i);
    const d = crypto.createDecipheriv('aes-128-gcm', key, iv);
    d.setAuthTag(r.slice(r.length - 16));
    return Buffer.concat([d.update(r.slice(4 + i, r.length - 16)), d.final()]);
}
const check = gcmDecrypt(workKey, Buffer.from(encPwd, 'hex')).toString('utf8');
if (check !== plain) { throw new Error('self-check failed'); }
console.log('material/ generated; encrypted password OK (' + encPwd.length + ' hex chars)');
