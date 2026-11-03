import { useEffect, useState } from 'react';
import { type AutomationSeed } from '../../api/automations';
import AutomationComposer from './AutomationComposer';
import { useToast } from '../../ui/toast/useToast';

export default function AutomationComposerHost() {
  const toast = useToast();
  const [seed, setSeed] = useState<AutomationSeed | null>(null);
  useEffect(() => {
    const open = (event: Event) => { setSeed((event as CustomEvent<AutomationSeed>).detail); };
    window.addEventListener('artverse:create-automation', open);
    return () => window.removeEventListener('artverse:create-automation', open);
  }, []);
  return <>{seed && <AutomationComposer seed={seed} onClose={() => setSeed(null)} onSaved={plan => { setSeed(null); toast.success('定时任务已创建', { action: { label: '查看任务', onClick: () => { window.location.hash = `#/automations/${plan.id}`; } } }); }} />}</>;
}
