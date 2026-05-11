import { useId } from "react";

export function ConvergenceGateIcon({ size = 60, className = "" }) {
  const uid = useId();
  const id = (name) => `${name}-${uid}`;

  const nodes = [
    { x: 18,  y: 22,  c: "#f97316" },
    { x: 100, y: 18,  c: "#10b981" },
    { x: 108, y: 70,  c: "#3b82f6" },
    { x: 90,  y: 104, c: "#8b5cf6" },
    { x: 18,  y: 98,  c: "#ef4444" },
    { x: 12,  y: 58,  c: "#06b6d4" },
  ];

  return (
    <svg
      viewBox="0 0 120 120"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      width={size}
      height={size}
      className={className}
    >
      <defs>
        <radialGradient id={id("cg-bg")} cx="50%" cy="40%" r="70%">
          <stop offset="0%" stopColor="#1a0533" />
          <stop offset="100%" stopColor="#090714" />
        </radialGradient>
        <radialGradient id={id("cg-core")} cx="40%" cy="35%" r="60%">
          <stop offset="0%" stopColor="#e9d5ff" />
          <stop offset="50%" stopColor="#a78bfa" />
          <stop offset="100%" stopColor="#5b21b6" stopOpacity="0.4" />
        </radialGradient>
        <filter id={id("cg-glow")}>
          <feGaussianBlur stdDeviation="2.5" result="b" />
          <feMerge>
            <feMergeNode in="b" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
        <filter id={id("cg-soft")}>
          <feGaussianBlur stdDeviation="4" />
        </filter>
        <clipPath id={id("cg-clip")}>
          <rect width="120" height="120" rx="26" />
        </clipPath>
      </defs>

      <g clipPath={`url(#${id("cg-clip")})`}>
        <rect width="120" height="120" fill={`url(#${id("cg-bg")})`} />
        <circle
          cx="60" cy="60" r="52"
          fill="#7c3aed" opacity="0.08"
          filter={`url(#${id("cg-soft")})`}
        />

        {nodes.map((d, i) => (
          <g key={i}>
            <line
              x1={d.x} y1={d.y} x2="60" y2="60"
              stroke={d.c} strokeWidth="0.8" opacity="0.25" strokeDasharray="3 3"
            />
            <circle
              cx={d.x} cy={d.y} r="7"
              fill={d.c} opacity="0.15"
              stroke={d.c} strokeWidth="1" strokeOpacity="0.7"
            />
          </g>
        ))}

        <polygon
          points="60,36 80,60 60,84 40,60"
          fill={`url(#${id("cg-core")})`}
          stroke="#a78bfa" strokeWidth="1.2" strokeOpacity="0.8"
        />
        <polygon
          points="60,38 62,60 60,82 58,60"
          fill="rgba(255,255,255,0.06)"
        />
        <ellipse cx="55" cy="48" rx="6" ry="3.5" fill="white" opacity="0.18" />
        <polygon
          points="60,36 80,60 60,84 40,60"
          fill="none"
          stroke="#e9d5ff" strokeWidth="0.5" opacity="0.5"
          filter={`url(#${id("cg-glow")})`}
        />
        <circle cx="60" cy="60" r="4" fill="#e9d5ff" filter={`url(#${id("cg-glow")})`} />
      </g>
    </svg>
  );
}
