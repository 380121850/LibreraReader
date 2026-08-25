import preferences from '@ohos.data.preferences';

/**
 * Reading progress persistence using @ohos.data.preferences.
 * Stores: recent book list (up to 20), per-book page position + timestamp,
 * star (favorite) flag, cover thumbnail path, and basic metadata.
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
  /** favorite flag */
  star: boolean;
  /** cached cover PNG path in sandbox ('' if not generated yet) */
  coverPath: string;
  /** author from metadata, if any */
  author: string;
  /* Sprint N: tags + series/genre metadata */
  /** user-assigned tags */
  tags: string[];
  /** book series name (e.g. "Harry Potter") */
  series: string;
  /** genre/category (e.g. "Fantasy", "Sci-Fi") */
  genre: string;
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
    const raw: object[] = JSON.parse(json) as object[];
    /* drop entries with a corrupted shape; backfill fields added later */
    const list: RecentBook[] = [];
    for (let i = 0; i < raw.length; i++) {
      const b = raw[i] as RecentBook;
      if (typeof b.path === 'string' && typeof b.title === 'string' && typeof b.page === 'number') {
        const clean: RecentBook = {
          path: b.path,
          title: b.title,
          page: typeof b.page === 'number' ? b.page : 0,
          totalPages: typeof b.totalPages === 'number' ? b.totalPages : 0,
          lastRead: typeof b.lastRead === 'string' ? b.lastRead : '',
          star: b.star === true,
          coverPath: typeof b.coverPath === 'string' ? b.coverPath : '',
          author: typeof b.author === 'string' ? b.author : '',
          tags: Array.isArray(b.tags) ? b.tags as string[] : [],
          series: typeof b.series === 'string' ? b.series : '',
          genre: typeof b.genre === 'string' ? b.genre : ''
        };
        list.push(clean);
      }
    }
    return list;
  } catch (e) {
    console.error('[Progress] getRecentBooks failed');
    return [];
  }
}

export async function getRecentBooksStarred(ctx: Context): Promise<RecentBook[]> {
  const all = await getRecentBooks(ctx);
  const starred: RecentBook[] = [];
  for (let i = 0; i < all.length; i++) {
    if (all[i].star) {
      starred.push(all[i]);
    }
  }
  return starred;
}

/** Toggle the favorite flag for a book path; returns the new star value. */
export async function toggleStar(ctx: Context, path: string): Promise<boolean> {
  const list = await getRecentBooks(ctx);
  let newStar = false;
  for (let i = 0; i < list.length; i++) {
    if (list[i].path === path) {
      list[i].star = !list[i].star;
      newStar = list[i].star;
      break;
    }
  }
  try {
    const prefs = await getPrefs(ctx);
    await prefs.put(KEY_RECENT, JSON.stringify(list));
    await prefs.flush();
    console.info(`[Progress] toggleStar ${path} -> ${newStar}`);
  } catch (e) {
    console.error('[Progress] toggleStar failed');
  }
  return newStar;
}

/** Update the cover thumbnail path for a book. */
export async function updateCover(ctx: Context, path: string, coverPath: string): Promise<void> {
  const list = await getRecentBooks(ctx);
  for (let i = 0; i < list.length; i++) {
    if (list[i].path === path) {
      list[i].coverPath = coverPath;
      break;
    }
  }
  try {
    const prefs = await getPrefs(ctx);
    await prefs.put(KEY_RECENT, JSON.stringify(list));
    await prefs.flush();
    console.info(`[Progress] updateCover ${path} -> ${coverPath}`);
  } catch (e) {
    console.error('[Progress] updateCover failed');
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

/** Full cleaned list, for backup export (sprint E). */
export async function exportRecentBooks(ctx: Context): Promise<RecentBook[]> {
  return await getRecentBooks(ctx);
}

/** Replace the whole recent list (backup restore, sprint E). */
export async function replaceRecentBooks(ctx: Context, list: RecentBook[]): Promise<void> {
  try {
    const prefs = await getPrefs(ctx);
    const trimmed: RecentBook[] = [];
    for (let i = 0; i < list.length && i < MAX_RECENT; i++) {
      trimmed.push(list[i]);
    }
    await prefs.put(KEY_RECENT, JSON.stringify(trimmed));
    await prefs.flush();
    console.info(`[Progress] Restored ${trimmed.length} recent books from backup`);
  } catch (e) {
    console.error('[Progress] replaceRecentBooks failed');
  }
}
