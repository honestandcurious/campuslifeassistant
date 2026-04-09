import axios from 'axios'

export const http = axios.create({
  baseURL: 'http://localhost:8088',
  timeout: 0,
  headers: {
    'Content-Type': 'application/json'
  }
})

export async function sendMessageStream({ message, memoryId = 'default-user', onChunk }) {
  const requestFailedText = '\u8bf7\u6c42\u5931\u8d25\uff0c\u72b6\u6001\u7801\uff1a'
  const streamUnsupportedText = '\u6d4f\u89c8\u5668\u4e0d\u652f\u6301\u6d41\u5f0f\u54cd\u5e94\u3002'

  const response = await fetch('http://localhost:8088/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Memory-Id': memoryId
    },
    body: JSON.stringify({ message })
  })

  if (!response.ok) {
    const text = await response.text()
    throw new Error(text || `${requestFailedText}${response.status}`)
  }

  if (!response.body) {
    throw new Error(streamUnsupportedText)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let sseBuffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      flushSseBuffer(sseBuffer, onChunk)
      break
    }

    const text = decoder.decode(value, { stream: true })
    if (!text) {
      continue
    }

    const normalizedText = text.replace(/\r\n/g, '\n')

    if (normalizedText.includes('data:') || sseBuffer) {
      sseBuffer += normalizedText
      sseBuffer = consumeSseBuffer(sseBuffer, onChunk)
      continue
    }

    emitPlainText(normalizedText, onChunk)
  }
}

function consumeSseBuffer(buffer, onChunk) {
  const events = buffer.split('\n\n')
  if (events.length === 1) {
    return buffer
  }

  const remaining = events.pop() || ''
  for (const eventText of events) {
    emitSseEvent(eventText, onChunk)
  }
  return remaining
}

function flushSseBuffer(buffer, onChunk) {
  const normalized = buffer.replace(/\r\n/g, '\n').trim()
  if (!normalized) {
    return
  }

  if (normalized.includes('data:')) {
    emitSseEvent(normalized, onChunk)
    return
  }

  emitPlainText(normalized, onChunk)
}

function emitSseEvent(eventText, onChunk) {
  const payload = eventText
    .split('\n')
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).trimStart())
    .join('\n')

  if (payload && payload !== '[DONE]') {
    onChunk(payload)
  }
}

function emitPlainText(text, onChunk) {
  const payload = text
    .replace(/^event:.*$/gm, '')
    .replace(/^data:\s?/gm, '')
    .replace(/\[DONE]/g, '')
    .trim()

  if (payload) {
    onChunk(payload)
  }
}
