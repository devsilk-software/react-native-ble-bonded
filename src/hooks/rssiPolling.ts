/**
 * Reads the signal strength now and again `interval` milliseconds after each read finishes, so reads never overlap.
 * Returns a function that stops it; a read still running then reports nothing.
 */
export function pollRssi(
  read: () => Promise<number>,
  interval: number,
  onReading: (rssi: number) => void,
  onError: (error: unknown) => void
): () => void {
  let isActive = true
  let timer: ReturnType<typeof setTimeout> | undefined
  const next = async () => {
    try {
      const rssi = await read()
      if (isActive) onReading(rssi)
    } catch (error) {
      if (isActive) onError(error)
    }
    if (isActive) timer = setTimeout(() => void next(), interval)
  }
  void next()
  return () => {
    isActive = false
    clearTimeout(timer)
  }
}
