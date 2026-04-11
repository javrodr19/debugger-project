import React from 'react'

export interface DecorationItem {
  type: 'tree' | 'lamp' | 'bench' | 'grass'
  key: string
  hasCat?: boolean
}

export function Decoration({ item }: { item: DecorationItem }) {
  switch (item.type) {
    case 'tree':
      return (
        <div className="pixel-tree" key={item.key}>
          <div className="tree-crown" />
          <div className="tree-trunk" />
        </div>
      )
    case 'lamp':
      return (
        <div className="street-light" key={item.key}>
          <div className="lamp-arm"><div className="lamp-bulb" /></div>
          <div className="lamp-pole" />
          <div className="lamp-cone" />
        </div>
      )
    case 'bench':
      return (
        <div className="pixel-bench" key={item.key}>
          <div className="bench-seat">
            {item.hasCat && <div className="cat-sleeping" />}
          </div>
          <div className="bench-leg left" />
          <div className="bench-leg right" />
        </div>
      )
    case 'grass':
      return (
        <div className="grass-patch" key={item.key}>
          <div className="grass-blade" /><div className="grass-blade" /><div className="grass-blade" /><div className="grass-blade" />
          <div className="grass-ground" />
        </div>
      )
    default:
      return null
  }
}

export function CityStatBadge({ count, label, color, bg }: { count: number; label: string; color: string; bg: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 5, background: bg, border: `1px solid ${color}33`, padding: '3px 8px', borderRadius: 5 }}>
      <span style={{ color, fontWeight: 700 }}>{count}</span>
      <span style={{ color: '#8b949e' }}>{label}</span>
    </div>
  )
}

export function seededRandom(seed: number) {
  let s = seed
  return () => {
    s = (s * 16807 + 0) % 2147483647
    return (s - 1) / 2147483646
  }
}

export function hashString(s: string) {
  let hash = 0
  for (let i = 0; i < s.length; i++) hash = Math.imul(31, hash) + s.charCodeAt(i) | 0
  return hash
}
