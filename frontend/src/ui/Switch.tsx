export type SwitchOrigin = { x: number; y: number };

type SwitchSize = 'sm' | 'md';

interface Props {
  checked: boolean;
  onChange: (checked: boolean, origin: SwitchOrigin) => void;
  ariaLabel: string;
  disabled?: boolean;
  size?: SwitchSize;
  className?: string;
}

export default function Switch({ checked, onChange, ariaLabel, disabled = false, size = 'md', className = '' }: Props) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={ariaLabel}
      disabled={disabled}
      onClick={(event) => {
        const rect = event.currentTarget.getBoundingClientRect();
        onChange(!checked, { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 });
      }}
      className={['switch', `switch--${size}`, className].filter(Boolean).join(' ')}
    >
      <span aria-hidden="true" className="switch__thumb" />
    </button>
  );
}
