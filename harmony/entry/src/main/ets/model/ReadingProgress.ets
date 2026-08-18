import preferences from '@ohos.data.preferences';

/**
 * Reading progress persistence using @ohos.data.preferences.
 * Stores: recent book list (up to 20), per-book page position + timestamp.
 */

export interface RecentBook {
  /** sandbox path where the PDF copy lives */
  path: string;
  /** original file name for display */
  title: string;
  /** last read page index (0-based) */
  page: number;
  /** total pages at time of save */
  totalPages: number;
  /** ISO timestamp of last read */
  lastRead: string;
}

const PREF_NAME = 'librera_reader';
const KEY_RECENT = 'recent_books';
const MAX_RECENT = 20;

let prefInstance: preferences.Preferences | undefined = undefined;

async function getPrefs(ctx: Context): Promise<preferences.Preferences> {
  if (prefInstance === undefined) {
    prefInstance = await preferences.getPreferences(ctx, PREF_NAME);
  }
  return prefInstance;
}

export async function saveRecentBook(ctx: Context, book: RecentBook): Promise<void> {
  try {
    const prefs = await getPrefs(ctx);
    const list = await getRecentBooks(ctx);
    /* Remove existing entry for same path */
    const filtered: RecentBook[] = [];
    for (let i = 0; i < list.length; i++) {
      if (list[i].path !== book.path) {
        filtered.push(list[i]);
      }
    }
    filtered.unshift(book);
    /* Cap at MAX_RECENT */
    const trimmed: RecentBook[] = [];
    for (let i = 0; i < filtered.length && i < MAX_RECENT; i++) {
      trimmed.push(filtered[i]);
    }
    await prefs.put(KEY_RECENT, JSON.stringify(trimmed));
    await prefs.flush();
    console.info(`[Progress] Saved recent: ${book.title} page=${book.page}/${book.totalPages}`);
  } catch (e) {
    console.error('[Progress] saveRecentBook failed');
  }
}

export async function getRecentBooks(ctx: Context): Promise<RecentBook[]> {
  try {
    const prefs = await getPrefs(ctx);
    const json: string = await prefs.get(KEY_RECENT, '[]') as string;
    if (json.length === 0 || json === '[]') {
      return [];
    }
    const arr: RecentBook[] = JSON.parse(json) as RecentBook[];
    return arr;
  } catch (e) {
    console.error('[Progress] getRecentBooks failed');
    return [];
  }
}

export async function getPageForPath(ctx: Context, path: string): Promise<number> {
  try {
    const list = await getRecentBooks(ctx);
    for (let i = 0; i < list.length; i++) {
      if (list[i].path === path) {
        return list[i].page;
      }
    }
  } catch (e) {
    /* ignore */
  }
  return 0;
}
