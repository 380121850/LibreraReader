import { RecentBook, getRecentBooks, saveRecentBook } from './ReadingProgress';

/**
 * Sprint N: Tag management for the book library.
 * Tags are stored per-book in RecentBook.tags; this module provides
 * aggregate queries and CRUD helpers.
 */

/** Get all unique tags across the entire library. */
export async function getTagList(ctx: Context): Promise<string[]> {
  const books = await getRecentBooks(ctx);
  const tagSet: string[] = [];
  for (let i = 0; i < books.length; i++) {
    const t = books[i].tags;
    for (let j = 0; j < t.length; j++) {
      if (!tagSet.includes(t[j])) {
        tagSet.push(t[j]);
      }
    }
  }
  return tagSet.sort();
}

/** Add a tag to a book (no-op if already present). */
export async function addTagToBook(ctx: Context, path: string, tag: string): Promise<void> {
  const books = await getRecentBooks(ctx);
  for (let i = 0; i < books.length; i++) {
    if (books[i].path === path) {
      if (!books[i].tags.includes(tag)) {
        books[i].tags.push(tag);
      }
      await saveRecentBook(ctx, books[i]);
      return;
    }
  }
}

/** Remove a tag from a book. */
export async function removeTagFromBook(ctx: Context, path: string, tag: string): Promise<void> {
  const books = await getRecentBooks(ctx);
  for (let i = 0; i < books.length; i++) {
    if (books[i].path === path) {
      books[i].tags = books[i].tags.filter((t: string) => t !== tag);
      await saveRecentBook(ctx, books[i]);
      return;
    }
  }
}

/** Get all unique series names in the library. */
export async function getSeriesList(ctx: Context): Promise<string[]> {
  const books = await getRecentBooks(ctx);
  const list: string[] = [];
  for (let i = 0; i < books.length; i++) {
    if (books[i].series.length > 0 && !list.includes(books[i].series)) {
      list.push(books[i].series);
    }
  }
  return list.sort();
}
