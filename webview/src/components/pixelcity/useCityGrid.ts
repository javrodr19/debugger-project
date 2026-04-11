import { useMemo, useCallback } from 'react'
import type { GraphNode } from '../../types'
import { seededRandom, DecorationItem } from './subcomponents/CityElements'

const BUILDINGS_PER_BLOCK = 4
const BLOCKS_PER_ROW = 3
const PERSON_COLORS = ['blue', 'green', 'purple', 'orange', 'red'] as const

export function useCityGrid(nodes: GraphNode[]) {
  const sortedNodes = useMemo(() => {
    const statusOrder: Record<string, number> = { ERROR: 0, WARNING: 1, HEALTHY: 2 }
    return [...nodes].sort((a, b) => {
      const sa = statusOrder[a.status] ?? 2
      const sb = statusOrder[b.status] ?? 2
      if (sa !== sb) return sa - sb
      return b.complexity - a.complexity
    })
  }, [nodes])

  const gridRows = useMemo(() => {
    const rows: GraphNode[][][] = []
    let currentNodes = [...sortedNodes]

    while (currentNodes.length > 0) {
      const rowBlocks: GraphNode[][] = []
      for (let i = 0; i < BLOCKS_PER_ROW; i++) {
        if (currentNodes.length === 0) break
        rowBlocks.push(currentNodes.splice(0, BUILDINGS_PER_BLOCK))
      }
      rows.push(rowBlocks)
    }
    return rows
  }, [sortedNodes])

  const generatePeople = useCallback((rowIdx: number) => {
    const rng = seededRandom(rowIdx * 137 + 7)
    const count = Math.floor(rng() * 4) + 3
    return Array.from({ length: count }, (_, i) => ({
      key: `person-${rowIdx}-${i}`,
      color: PERSON_COLORS[Math.floor(rng() * PERSON_COLORS.length)],
      direction: rng() > 0.5 ? 'walk-right' : 'walk-left',
      top: `${rng() * 6 + 4}px`,
      delay: `${rng() * 8}s`,
      duration: `${rng() * 6 + 10}s`,
    }))
  }, [])

  const generateDecorations = useCallback((blockIdx: number, rowIdx: number, buildingCount: number) => {
    const rng = seededRandom(blockIdx * 53 + rowIdx * 17 + 13)
    const items: DecorationItem[] = []

    items.push({ type: 'lamp', key: `l-start-${rowIdx}-${blockIdx}` })

    for (let i = 0; i < buildingCount - 1; i++) {
      const roll = rng()
      if (roll < 0.35) items.push({ type: 'tree', key: `t-${rowIdx}-${blockIdx}-${i}` })
      else if (roll < 0.5) items.push({ type: 'bench', key: `b-${rowIdx}-${blockIdx}-${i}`, hasCat: rng() > 0.7 })
      else if (roll < 0.65) items.push({ type: 'grass', key: `g-${rowIdx}-${blockIdx}-${i}` })
    }

    if (rng() > 0.5) items.push({ type: 'tree', key: `t-end-${rowIdx}-${blockIdx}` })
    else items.push({ type: 'lamp', key: `l-end-${rowIdx}-${blockIdx}` })

    return items
  }, [])

  return { sortedNodes, gridRows, generatePeople, generateDecorations }
}
