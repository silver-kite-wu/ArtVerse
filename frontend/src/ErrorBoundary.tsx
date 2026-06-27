import { Component, type ReactNode } from 'react';

export default class ErrorBoundary extends Component<
  { children: ReactNode },
  { hasError: boolean; error?: string }
> {
  constructor(props: { children: ReactNode }) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(error: Error) {
    return { hasError: true, error: error.message };
  }

  override render() {
    if (this.state.hasError) {
      return (
        <div className="flex min-h-dvh items-center justify-center bg-paper-base px-6 text-sumi">
          <div className="max-w-md rounded-lg border border-vermilion/20 bg-vermilion-light/20 p-5">
            <div className="text-sm font-medium text-vermilion">应用出错</div>
            <div className="mt-2 text-sm text-sumi-dim">{this.state.error || 'Unknown error'}</div>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
