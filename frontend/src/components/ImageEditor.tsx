import { useEffect, useRef, useState } from 'react';
import {
  RotateCw,
  RotateCcw,
  FlipHorizontal,
  FlipVertical,
  Check,
  X,
  Crop as CropIcon,
} from 'lucide-react';

interface CropRect {
  x: number;
  y: number;
  w: number;
  h: number;
}

type DragHandle = 'nw' | 'ne' | 'sw' | 'se' | 'n' | 's' | 'w' | 'e' | 'move' | null;

const ASPECT = 3 / 4;

function clamp(v: number, min: number, max: number) {
  return Math.max(min, Math.min(max, v));
}

function ImageEditor({
  imageDataUrl,
  onConfirm,
  onCancel,
}: {
  imageDataUrl: string;
  onConfirm: (croppedBase64: string) => void;
  onCancel: () => void;
}) {
  const imageRef = useRef<HTMLImageElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const [imgNatural, setImgNatural] = useState({ w: 0, h: 0 });
  const [baseDisplay, setBaseDisplay] = useState({ w: 0, h: 0 });
  const [cropRect, setCropRect] = useState<CropRect | null>(null);
  const [rotation, setRotation] = useState(0);
  const [flipH, setFlipH] = useState(false);
  const [flipV, setFlipV] = useState(false);
  const [cursor, setCursor] = useState('default');

  const dragHandleRef = useRef<DragHandle>(null);
  const dragStartRef = useRef({ x: 0, y: 0 });
  const dragRectRef = useRef<CropRect>({ x: 0, y: 0, w: 0, h: 0 });

  // Load image
  useEffect(() => {
    if (!imageDataUrl) return;
    const img = new Image();
    img.onload = () => {
      const iw = img.naturalWidth;
      const ih = img.naturalHeight;
      setImgNatural({ w: iw, h: ih });
      // Base size: fit longest side to 500px
      const maxDim = 500;
      let s = 1;
      if (iw > maxDim || ih > maxDim) {
        s = maxDim / Math.max(iw, ih);
      }
      setBaseDisplay({ w: Math.round(iw * s), h: Math.round(ih * s) });
      initCrop(iw, ih);
    };
    img.src = imageDataUrl;
  }, [imageDataUrl]);

  function initCrop(iw: number, ih: number) {
    let cw: number, ch: number;
    if (iw / ih > ASPECT) { ch = ih; cw = ch * ASPECT; }
    else { cw = iw; ch = cw / ASPECT; }
    setCropRect({ x: (iw - cw) / 2, y: (ih - ch) / 2, w: cw, h: ch });
  }

  // Coord conversion: screen → image natural coords
  const screenToImage = (cx: number, cy: number) => {
    const el = imageRef.current;
    if (!el) return { x: 0, y: 0 };
    const r = el.getBoundingClientRect();
    const sx = imgNatural.w / r.width;
    const sy = imgNatural.h / r.height;
    return {
      x: clamp((cx - r.left) * sx, 0, imgNatural.w),
      y: clamp((cy - r.top) * sy, 0, imgNatural.h),
    };
  };

  const getHandle = (cx: number, cy: number): DragHandle => {
    if (!cropRect || !imageRef.current) return null;
    const p = screenToImage(cx, cy);
    const r = cropRect;
    const m = 16;
    const L = Math.abs(p.x - r.x) < m;
    const R = Math.abs(p.x - (r.x + r.w)) < m;
    const T = Math.abs(p.y - r.y) < m;
    const B = Math.abs(p.y - (r.y + r.h)) < m;
    if (T && L) return 'nw'; if (T && R) return 'ne';
    if (B && L) return 'sw'; if (B && R) return 'se';
    if (T) return 'n'; if (B) return 's'; if (L) return 'w'; if (R) return 'e';
    if (p.x >= r.x && p.x <= r.x + r.w && p.y >= r.y && p.y <= r.y + r.h) return 'move';
    return null;
  };

  const cursors: Record<string, string> = {
    nw: 'nw-resize', ne: 'ne-resize', sw: 'sw-resize', se: 'se-resize',
    n: 'n-resize', s: 's-resize', w: 'w-resize', e: 'e-resize',
    move: 'move',
  };

  const handleMouseDown = (e: React.MouseEvent) => {
    if (e.button !== 0 || !cropRect || rotation !== 0) return;
    const h = getHandle(e.clientX, e.clientY);
    if (!h) return;
    dragHandleRef.current = h;
    dragStartRef.current = screenToImage(e.clientX, e.clientY);
    dragRectRef.current = { ...cropRect };
    e.preventDefault();
  };

  const handleMouseMove = (e: React.MouseEvent) => {
    if (!imageRef.current || !cropRect) return;
    if (!dragHandleRef.current) {
      setCursor(rotation !== 0 ? 'default' : cursors[getHandle(e.clientX, e.clientY) || ''] || 'default');
      return;
    }
    const pos = screenToImage(e.clientX, e.clientY);
    const dx = pos.x - dragStartRef.current.x;
    const dy = pos.y - dragStartRef.current.y;
    const r = dragRectRef.current;
    let nr = { ...r };
    const clampW = (v: number) => clamp(v, 40, imgNatural.w);
    const clampH = (v: number) => clamp(v, 40, imgNatural.h);

    switch (dragHandleRef.current) {
      case 'se': {
        nr.w = clampW(r.w + dx);
        nr.h = nr.w / ASPECT; nr.w = nr.h * ASPECT; nr.w = clampW(nr.w); nr.h = nr.w / ASPECT;
        break;
      }
      case 'nw': {
        let w2 = r.w - dx; let h2 = w2 / ASPECT; w2 = h2 * ASPECT;
        w2 = clampW(w2); h2 = w2 / ASPECT;
        nr.x = r.x + (r.w - w2); nr.y = r.y + (r.h - h2); nr.w = w2; nr.h = h2;
        break;
      }
      case 'ne': {
        let w2 = r.w + dx; let h2 = w2 / ASPECT; w2 = h2 * ASPECT;
        w2 = clampW(w2); h2 = w2 / ASPECT;
        nr.y = r.y + (r.h - h2); nr.w = w2; nr.h = h2;
        break;
      }
      case 'sw': {
        let w2 = r.w - dx; let h2 = w2 / ASPECT; w2 = h2 * ASPECT;
        w2 = clampW(w2); h2 = w2 / ASPECT;
        nr.x = r.x + (r.w - w2); nr.w = w2; nr.h = h2;
        break;
      }
      case 'n': {
        let h2 = clampH(r.h - dy);
        let w2 = h2 * ASPECT;
        if (w2 > r.w) { w2 = r.w; h2 = w2 / ASPECT; } else if (r.x + w2 > imgNatural.w) { w2 = imgNatural.w - r.x; h2 = w2 / ASPECT; }
        nr.x = r.x + (r.w - w2); nr.w = w2; nr.y = r.y + (r.h - h2); nr.h = h2;
        break;
      }
      case 's': {
        let h2 = clampH(r.h + dy);
        let w2 = h2 * ASPECT;
        if (r.x + w2 > imgNatural.w) { w2 = imgNatural.w - r.x; h2 = w2 / ASPECT; }
        nr.w = w2; nr.h = h2;
        break;
      }
      case 'w': {
        let w2 = clampW(r.w - dx);
        let h2 = w2 / ASPECT;
        if (h2 > r.h) { h2 = r.h; w2 = h2 * ASPECT; } else if (r.y + h2 > imgNatural.h) { h2 = imgNatural.h - r.y; w2 = h2 * ASPECT; }
        nr.x = r.x + (r.w - w2); nr.w = w2; nr.y = r.y + (r.h - h2); nr.h = h2;
        break;
      }
      case 'e': {
        let w2 = clampW(r.w + dx);
        let h2 = w2 / ASPECT;
        if (r.y + h2 > imgNatural.h) { h2 = imgNatural.h - r.y; w2 = h2 * ASPECT; }
        nr.w = w2; nr.h = h2;
        break;
      }
      case 'move': {
        nr.x = clamp(r.x + dx, 0, imgNatural.w - r.w);
        nr.y = clamp(r.y + dy, 0, imgNatural.h - r.h);
        break;
      }
    }
    setCropRect(nr);
  };

  const handleMouseUp = () => { dragHandleRef.current = null; };

  const handleConfirm = () => {
    if (!cropRect) return;
    const c = document.createElement('canvas');
    const ctx = c.getContext('2d');
    if (!ctx) return;
    c.width = Math.round(cropRect.w);
    c.height = Math.round(cropRect.h);
    const img = new Image();
    img.onload = () => {
      ctx.drawImage(img, cropRect.x, cropRect.y, cropRect.w, cropRect.h, 0, 0, c.width, c.height);
      onConfirm(c.toDataURL('image/jpeg', 0.92).split(',')[1]);
    };
    img.src = imageDataUrl;
  };

  const handleRotate = (dir: 1 | -1) => {
    setRotation((r) => (r + dir * 90 + 360) % 360);
    if (cropRect) initCrop(imgNatural.w, imgNatural.h);
  };

  // Crop box display coords (% of the displayed image area)
  const s = cropRect ? {
    left: `${(cropRect.x / imgNatural.w) * 100}%`,
    top: `${(cropRect.y / imgNatural.h) * 100}%`,
    width: `${(cropRect.w / imgNatural.w) * 100}%`,
    height: `${(cropRect.h / imgNatural.h) * 100}%`,
  } : null;

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-sumi/40 backdrop-blur-sm p-3 sm:p-4">
      <div
        ref={containerRef}
        className="bg-paper-raised border border-paper-border rounded-xl w-full max-w-4xl shadow-modal overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-3 border-b border-paper-border">
          <h3 className="text-sm font-semibold flex items-center gap-2 text-sumi">
            <CropIcon size={16} className="text-vermilion" />
            编辑封面
          </h3>
          <button onClick={onCancel} className="p-1 text-sumi-faint hover:text-sumi-dim transition-colors">
            <X size={16} />
          </button>
        </div>

        {/* Body */}
        <div className="flex flex-col sm:flex-row">
          {/* Left — Image preview (fixed-size container) */}
          <div className="flex-1 min-h-[350px] flex items-center justify-center bg-black select-none overflow-hidden"
            onMouseDown={handleMouseDown}
            onMouseMove={handleMouseMove}
            onMouseUp={handleMouseUp}
            onMouseLeave={handleMouseUp}
          >
            {/* Wrapper stays fixed size. Only the image scales. */}
            <div
              className="relative shrink-0 overflow-hidden"
              style={{ width: baseDisplay.w, height: baseDisplay.h, cursor }}
            >
              <img
                ref={imageRef}
                src={imageDataUrl}
                alt="编辑"
                className="block"
                draggable={false}
                style={{
                  width: baseDisplay.w,
                  height: baseDisplay.h,
                  transform: `rotate(${rotation}deg) scaleX(${flipH ? -1 : 1}) scaleY(${flipV ? -1 : 1})`,
                  transformOrigin: 'center center',
                }}
              />

              {/* Overlay bands */}
              {cropRect && s && (
                <div className="absolute inset-0 pointer-events-none">
                  <div className="absolute left-0 right-0 bg-black/45" style={{ top: 0, height: s.top }} />
                  <div className="absolute left-0 right-0 bg-black/45" style={{ bottom: 0, height: `calc(100% - ${s.top} - ${s.height})` }} />
                  <div className="absolute bg-black/45" style={{ top: s.top, left: 0, width: s.left, height: s.height }} />
                  <div className="absolute bg-black/45" style={{ top: s.top, right: 0, width: `calc(100% - ${s.left} - ${s.width})`, height: s.height }} />
                </div>
              )}

              {/* Crop box */}
              {cropRect && s && (
                <div className="absolute pointer-events-none" style={s}>
                  <div className="absolute inset-0 border-2 border-white/90 rounded-sm shadow-[inset_0_0_0_1px_rgba(0,0,0,0.15)]" />
                  <div className="absolute -top-[7px] -left-[7px] w-[14px] h-[14px] bg-white border-2 border-coral rounded-sm shadow-md" />
                  <div className="absolute -top-[7px] -right-[7px] w-[14px] h-[14px] bg-white border-2 border-coral rounded-sm shadow-md" />
                  <div className="absolute -bottom-[7px] -left-[7px] w-[14px] h-[14px] bg-white border-2 border-coral rounded-sm shadow-md" />
                  <div className="absolute -bottom-[7px] -right-[7px] w-[14px] h-[14px] bg-white border-2 border-coral rounded-sm shadow-md" />
                  <div className="absolute top-1/2 -left-[5px] -translate-y-1/2 w-[10px] h-[10px] bg-white border border-coral rounded-sm shadow-sm" />
                  <div className="absolute top-1/2 -right-[5px] -translate-y-1/2 w-[10px] h-[10px] bg-white border border-coral rounded-sm shadow-sm" />
                  <div className="absolute left-1/2 -top-[5px] -translate-x-1/2 w-[10px] h-[10px] bg-white border border-coral rounded-sm shadow-sm" />
                  <div className="absolute left-1/2 -bottom-[5px] -translate-x-1/2 w-[10px] h-[10px] bg-white border border-coral rounded-sm shadow-sm" />
                </div>
              )}
            </div>
          </div>

          {/* Right — Tool panel */}
          <div className="w-full sm:w-56 shrink-0 border-t sm:border-t-0 sm:border-l border-paper-border bg-paper-surface/50 flex flex-col">
            {/* Rotate & Flip */}
            <div className="px-4 py-3">
              <div className="text-[11px] font-medium text-sumi-dim uppercase tracking-wider mb-2">旋转 & 翻转</div>
              <div className="flex flex-wrap gap-1.5">
                <button
                  onClick={() => handleRotate(-1)}
                  className="flex items-center gap-1.5 rounded-md border border-paper-border px-3 py-2 text-xs text-sumi-dim hover:text-sumi hover:bg-paper-base transition-colors bg-paper-base"
                >
                  <RotateCcw size={14} />
                  左旋
                </button>
                <button
                  onClick={() => handleRotate(1)}
                  className="flex items-center gap-1.5 rounded-md border border-paper-border px-3 py-2 text-xs text-sumi-dim hover:text-sumi hover:bg-paper-base transition-colors bg-paper-base"
                >
                  <RotateCw size={14} />
                  右旋
                </button>
                <button
                  onClick={() => setFlipH((v) => !v)}
                  className={'flex items-center gap-1.5 rounded-md border px-3 py-2 text-xs transition-colors ' + (flipH ? 'border-vermilion/40 bg-vermilion-light/30 text-vermilion' : 'border-paper-border text-sumi-dim hover:text-sumi hover:bg-paper-base bg-paper-base')}
                >
                  <FlipHorizontal size={14} />
                  翻转
                </button>
                <button
                  onClick={() => setFlipV((v) => !v)}
                  className={'flex items-center gap-1.5 rounded-md border px-3 py-2 text-xs transition-colors ' + (flipV ? 'border-vermilion/40 bg-vermilion-light/30 text-vermilion' : 'border-paper-border text-sumi-dim hover:text-sumi hover:bg-paper-base bg-paper-base')}
                >
                  <FlipVertical size={14} />
                  垂直
                </button>
              </div>
            </div>

            <div className="flex-1" />

            {/* Actions */}
            <div className="px-4 py-3 border-t border-paper-border flex items-center gap-2">
              <button
                onClick={onCancel}
                className="flex-1 px-3 py-2 text-sm text-sumi-dim hover:text-sumi transition-colors rounded-md border border-paper-border bg-paper-base"
              >
                取消
              </button>
              <button
                onClick={handleConfirm}
                className="flex-1 flex items-center justify-center gap-1.5 px-3 py-2 bg-vermilion hover:bg-vermilion-hover text-white text-sm font-medium rounded-md transition-colors"
              >
                <Check size={14} />
                应用
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

export default ImageEditor;
