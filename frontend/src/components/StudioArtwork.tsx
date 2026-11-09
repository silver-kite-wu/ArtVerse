import { useId } from 'react';

/** Decorative artwork drawn locally so the welcome panel has no network dependency. */
export default function StudioArtwork() {
  const id = useId().replace(/:/g, '');
  return (
    <svg className="studio-artwork" viewBox="0 0 520 330" fill="none" aria-hidden="true">
      <defs>
        <linearGradient id={`${id}-sky`} x1="300" y1="30" x2="300" y2="280" gradientUnits="userSpaceOnUse">
          <stop stopColor="#2E2E33" /><stop offset=".52" stopColor="#1A1A1E" /><stop offset="1" stopColor="#0C0C0E" />
        </linearGradient>
        <linearGradient id={`${id}-paper`} x1="190" y1="190" x2="280" y2="292" gradientUnits="userSpaceOnUse">
          <stop stopColor="#F0EFEA" /><stop offset="1" stopColor="#BDBAB2" />
        </linearGradient>
        <clipPath id={`${id}-arch`}><path d="M221 277V115a91 91 0 0 1 182 0v162Z" /></clipPath>
      </defs>
      <circle cx="322" cy="162" r="147" stroke="#E7DEC9" strokeOpacity=".14" />
      <circle cx="322" cy="162" r="124" stroke="#E7DEC9" strokeOpacity=".10" />
      <path d="M210 278V115a102 102 0 0 1 204 0v163" stroke="#55555C" strokeOpacity=".8" />
      <g clipPath={`url(#${id}-arch)`}>
        <path d="M221 24h182v254H221z" fill={`url(#${id}-sky)`} />
        <circle cx="339" cy="102" r="31" fill="#E7DEC9" />
        <circle cx="339" cy="102" r="46" stroke="#E7DEC9" strokeOpacity=".20" />
        <path d="m188 219 81-91 53 62 46-44 70 80v72H188Z" fill="#3A3A40" />
        <path d="m191 271 73-69 47 31 62-64 68 97v34H191Z" fill="#26262B" />
        <path d="m255 300 52-69 28 8 34-33-25 49-43 45Z" fill="#1F1F24" />
        <path d="m340 276 48-40 50 34v30h-98Z" fill="#141417" />
      </g>
      <ellipse cx="282" cy="296" rx="184" ry="15" fill="#000" fillOpacity=".45" />
      <g transform="rotate(-13 175 228)">
        <rect x="122" y="124" width="90" height="154" rx="5" fill="#3A3A40" />
        <path d="M130 125v151" stroke="#7A746A" strokeWidth="2" />
        <rect x="142" y="146" width="48" height="67" rx="24" stroke="#D9D2C2" strokeOpacity=".65" />
        <circle cx="166" cy="173" r="11" fill="#D9D2C2" />
        <path d="m145 201 21-19 22 19M145 235h44m-35 8h26" stroke="#D9D2C2" strokeOpacity=".7" />
      </g>
      <path d="m172 261 100-20 124 28-102 35Z" fill="#4A4A50" />
      <path d="m172 253 100-21 124 29v8l-102 28-122-28Z" fill="#D9D5CB" />
      <path d="m172 253 100-21 124 29-102 29Z" fill="#F4F2EC" />
      <path d="M282 244c-25-23-59-34-105-30l-14 39c50-1 89 10 121 27 28-19 66-27 111-25l-15-39c-41-4-74 6-98 28Z" fill={`url(#${id}-paper)`} />
      <path d="m282 244 2 36m-93-53c32 0 58 7 79 19m-81-9c32 1 57 8 80 20m24-10c22-14 45-20 74-20m-72 31c22-13 46-19 75-20" stroke="#9A9A9C" strokeOpacity=".6" strokeWidth="1.5" />
      <path d="M424 279c-4-37 1-70 16-101m-14 75c-21-5-31-18-31-37 20 4 30 16 31 37m1-22c17-1 29-12 34-30-20 0-31 11-34 30m8-29c-12-8-16-19-12-34 13 9 17 20 12 34" stroke="#7C7C82" strokeWidth="2" fill="#3F3F45" />
      <path d="m411 274 30-1-5 25h-20Z" fill="#55555C" />
      <path d="m109 93 3 9 9 3-9 3-3 9-3-9-9-3 9-3ZM440 118l2 6 7 2-7 2-2 7-2-7-6-2 6-2Z" fill="#E7DEC9" />
      <circle cx="170" cy="75" r="3" fill="#E7DEC9" fillOpacity=".7" />
      <path d="M125 183c-25-9-43-26-48-51m9 10-9-10-3 14" stroke="#9A9A9C" strokeOpacity=".55" strokeLinecap="round" />
    </svg>
  );
}
