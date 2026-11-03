import { useEffect, useState } from 'react';
import { watchImageRequest, type ImageRequestSnapshot } from '../api/imageRequests';

export function useImageGenerationRequest(requestId: string | null) {
  const [snapshot, setSnapshot] = useState<ImageRequestSnapshot | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    if (!requestId) return;
    const watcher = watchImageRequest(requestId, next => { setSnapshot(next);setError(''); }, error => setError(error.message));
    return () => watcher.abort();
  }, [requestId]);
  return { snapshot: snapshot?.request_id === requestId ? snapshot : null, error };
}
