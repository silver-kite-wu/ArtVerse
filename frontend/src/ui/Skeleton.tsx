import type { ComponentPropsWithoutRef } from 'react';

type Props = ComponentPropsWithoutRef<'div'>;

/** Neutral loading placeholder with a travelling highlight band. */
export default function Skeleton({ className = '', ...props }: Props) {
  return <div aria-hidden="true" className={['skeleton', className].filter(Boolean).join(' ')} {...props} />;
}
