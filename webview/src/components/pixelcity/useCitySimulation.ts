import { useState, useEffect, useMemo } from 'react'
import { seededRandom } from './subcomponents/CityElements'

export function useCitySimulation() {
  const [showClown, setShowClown] = useState(false)
  const [showUfo, setShowUfo] = useState(false)

  useEffect(() => {
    const clownInterval = setInterval(() => {
      if (Math.random() > 0.6) {
        setShowClown(true)
        setTimeout(() => setShowClown(false), 15000)
      }
    }, 45000)

    const ufoInterval = setInterval(() => {
      if (Math.random() > 0.5) {
        setShowUfo(true)
        setTimeout(() => setShowUfo(false), 35000)
      }
    }, 60000)

    const initialUfo = setTimeout(() => { 
        if (Math.random() > 0.5) {
            setShowUfo(true)
            setTimeout(() => setShowUfo(false), 35000)
        }
    }, 5000)

    return () => {
      clearInterval(clownInterval)
      clearInterval(ufoInterval)
      clearTimeout(initialUfo)
    }
  }, [])

  const stars = useMemo(() => {
    const rng = seededRandom(42)
    return Array.from({ length: 40 }, (_, i) => ({
      key: i,
      left: `${rng() * 100}%`,
      top: `${rng() * 50 + 5}px`,
      delay: `${rng() * 3}s`,
      size: rng() > 0.7 ? 3 : 2,
    }))
  }, [])

  const clouds = useMemo(() => {
    const rng = seededRandom(99)
    return Array.from({ length: 6 }, (_, i) => ({
      key: i,
      top: `${rng() * 30 + 10}px`,
      delay: `${rng() * 20}s`,
      width: `${rng() * 16 + 20}px`,
    }))
  }, [])

  return { showClown, showUfo, stars, clouds }
}
