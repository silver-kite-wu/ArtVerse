const DB_NAME = 'artverse-image-cache';
const DB_VERSION = 1;
const STORE_NAME = 'images';
const MAX_ENTRIES = 100;

interface CachedImageEntry {
  key: string;
  blob: Blob;
  updatedAt: number;
}

let databasePromise: Promise<IDBDatabase> | null = null;
const inFlightLoads = new Map<string, Promise<Blob>>();

function openDatabase(): Promise<IDBDatabase> {
  if (!('indexedDB' in window)) {
    return Promise.reject(new Error('IndexedDB is unavailable'));
  }
  if (databasePromise) return databasePromise;

  databasePromise = new Promise((resolve, reject) => {
    const request = window.indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains(STORE_NAME)) {
        const store = database.createObjectStore(STORE_NAME, { keyPath: 'key' });
        store.createIndex('updatedAt', 'updatedAt');
      }
    };
    request.onsuccess = () => {
      const database = request.result;
      database.onversionchange = () => {
        database.close();
        databasePromise = null;
      };
      resolve(database);
    };
    request.onerror = () => {
      databasePromise = null;
      reject(request.error ?? new Error('Failed to open image cache'));
    };
    request.onblocked = () => {
      databasePromise = null;
      reject(new Error('Image cache upgrade was blocked'));
    };
  });
  return databasePromise;
}

function waitForTransaction(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error ?? new Error('Image cache transaction failed'));
    transaction.onabort = () => reject(transaction.error ?? new Error('Image cache transaction was aborted'));
  });
}

async function trimOldEntries(database: IDBDatabase): Promise<void> {
  const countTransaction = database.transaction(STORE_NAME, 'readonly');
  const countRequest = countTransaction.objectStore(STORE_NAME).count();
  const count = await new Promise<number>((resolve, reject) => {
    countRequest.onsuccess = () => resolve(countRequest.result);
    countRequest.onerror = () => reject(countRequest.error);
  });
  await waitForTransaction(countTransaction);

  let entriesToDelete = count - MAX_ENTRIES;
  if (entriesToDelete <= 0) return;

  const deleteTransaction = database.transaction(STORE_NAME, 'readwrite');
  const index = deleteTransaction.objectStore(STORE_NAME).index('updatedAt');
  const cursorRequest = index.openKeyCursor();
  cursorRequest.onsuccess = () => {
    const cursor = cursorRequest.result;
    if (!cursor || entriesToDelete <= 0) return;
    deleteTransaction.objectStore(STORE_NAME).delete(cursor.primaryKey);
    entriesToDelete -= 1;
    cursor.continue();
  };
  await waitForTransaction(deleteTransaction);
}

export function generatedImageCacheKey(objectKey: string): string {
  return `generated:${objectKey}`;
}

export function referenceImageCacheKey(messageId: string, index: number): string {
  return `reference:${messageId}:${index}`;
}

async function getCachedImage(key: string): Promise<Blob | null> {
  try {
    const database = await openDatabase();
    const transaction = database.transaction(STORE_NAME, 'readonly');
    const request = transaction.objectStore(STORE_NAME).get(key);
    const entry = await new Promise<CachedImageEntry | undefined>((resolve, reject) => {
      request.onsuccess = () => resolve(request.result as CachedImageEntry | undefined);
      request.onerror = () => reject(request.error);
    });
    await waitForTransaction(transaction);
    return entry?.blob ?? null;
  } catch {
    return null;
  }
}

export async function cacheImage(key: string, blob: Blob): Promise<boolean> {
  try {
    const database = await openDatabase();
    const transaction = database.transaction(STORE_NAME, 'readwrite');
    transaction.objectStore(STORE_NAME).put({ key, blob, updatedAt: Date.now() } satisfies CachedImageEntry);
    await waitForTransaction(transaction);
    void trimOldEntries(database).catch(() => {});
    return true;
  } catch {
    return false;
  }
}

export async function deleteCachedImage(key: string): Promise<void> {
  try {
    const database = await openDatabase();
    const transaction = database.transaction(STORE_NAME, 'readwrite');
    transaction.objectStore(STORE_NAME).delete(key);
    await waitForTransaction(transaction);
  } catch {
    // Cache cleanup must not block server-side deletion.
  }
}

export async function loadPersistentImage(key: string, sourceUrl?: string): Promise<Blob> {
  const existingLoad = inFlightLoads.get(key);
  if (existingLoad) return existingLoad;

  const load = (async () => {
    const cached = await getCachedImage(key);
    if (cached) return cached;
    if (!sourceUrl) throw new Error('Image is not available in the local cache');

    const response = await fetch(sourceUrl, { credentials: 'same-origin' });
    if (!response.ok) throw new Error(`Image request failed with HTTP ${response.status}`);
    const blob = await response.blob();
    if (!blob.type.startsWith('image/')) throw new Error('Image request returned an invalid content type');
    await cacheImage(key, blob);
    return blob;
  })();

  inFlightLoads.set(key, load);
  try {
    return await load;
  } finally {
    if (inFlightLoads.get(key) === load) inFlightLoads.delete(key);
  }
}
