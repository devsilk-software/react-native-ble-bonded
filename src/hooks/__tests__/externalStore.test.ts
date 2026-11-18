import { createExternalStore } from '../externalStore'

function nativeListener() {
  let emit: ((value: string) => void) | undefined
  const remove = jest.fn()
  const listen = jest.fn((onChange: (value: string) => void) => {
    emit = onChange
    return { remove }
  })
  return { listen, remove, emit: (value: string) => emit?.(value) }
}

describe('Hook store', () => {
  it('starts the native listener with the first component and stops it with the last', () => {
    const native = nativeListener()
    const store = createExternalStore(native.listen, 'unknown')

    const unsubscribeFirst = store.subscribe(jest.fn())
    const unsubscribeSecond = store.subscribe(jest.fn())
    unsubscribeFirst()

    expect(native.listen).toHaveBeenCalledTimes(1)
    expect(native.remove).not.toHaveBeenCalled()

    unsubscribeSecond()

    expect(native.remove).toHaveBeenCalledTimes(1)
  })

  it('reports the initial value until the first report, then re-renders subscribed components on each change', () => {
    const native = nativeListener()
    const store = createExternalStore(native.listen, 'unknown')
    const rerender = jest.fn()
    store.subscribe(rerender)

    expect(store.getSnapshot()).toBe('unknown')

    native.emit('poweredOn')
    native.emit('poweredOn')
    native.emit('poweredOff')

    expect(store.getSnapshot()).toBe('poweredOff')
    expect(rerender).toHaveBeenCalledTimes(2)
  })

  it('starts again from the initial value after the last component leaves', () => {
    const native = nativeListener()
    const store = createExternalStore(native.listen, 'unknown')
    const unsubscribe = store.subscribe(jest.fn())
    native.emit('poweredOn')

    unsubscribe()

    expect(store.getSnapshot()).toBe('unknown')
  })
})
