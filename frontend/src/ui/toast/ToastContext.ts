import { createContext } from 'react';

export type ToastTone = 'success' | 'error' | 'warning' | 'info';

export interface ToastAction {
  label: string;
  onClick: () => void;
}

export interface ToastOptions {
  tone?: ToastTone;
  duration?: number | null;
  action?: ToastAction;
}

export interface ToastApi {
  show: (message: string, options?: ToastOptions) => string;
  success: (message: string, options?: Omit<ToastOptions, 'tone'>) => string;
  error: (message: string, options?: Omit<ToastOptions, 'tone'>) => string;
  warning: (message: string, options?: Omit<ToastOptions, 'tone'>) => string;
  info: (message: string, options?: Omit<ToastOptions, 'tone'>) => string;
  dismiss: (id: string) => void;
}

export const ToastContext = createContext<ToastApi | null>(null);
