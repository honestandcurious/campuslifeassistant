<template>
  <div class="app-shell">
    <div class="chat-card">
      <ChatHeader />

      <ChatMessageList
        ref="messageListRef"
        :messages="messages"
        :loading="loading && !streamingStarted"
      />

      <ChatInput
        v-model="inputValue"
        :loading="loading"
        @send="handleSend"
      />
    </div>
  </div>
</template>

<script setup>
import { nextTick, ref } from 'vue'
import { sendMessageStream } from './api'
import ChatHeader from './components/ChatHeader.vue'
import ChatInput from './components/ChatInput.vue'
import ChatMessageList from './components/ChatMessageList.vue'

const welcomeMessage =
  '\u4f60\u597d\uff0c\u6211\u662f\u6821\u56ed\u667a\u80fd\u52a9\u624b\u3002\u4f60\u53ef\u4ee5\u76f4\u63a5\u95ee\u6211\u8bfe\u8868\u3001\u8bfe\u7a0b\u8d44\u6599\u3001\u63d0\u9192\u3001\u5929\u6c14\u6216\u51fa\u884c\u95ee\u9898\u3002'

const invalidReplyMessage = '\u672a\u6536\u5230\u6709\u6548\u56de\u590d\u3002'
const requestFailedPrefix = '\u8bf7\u6c42\u5931\u8d25\uff1a'
const requestFailedFallback = '\u8bf7\u6c42\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002'
const defaultMemoryId = 'default-user'

const messageListRef = ref(null)
const inputValue = ref('')
const loading = ref(false)
const streamingStarted = ref(false)
const messages = ref([
  {
    id: crypto.randomUUID(),
    role: 'assistant',
    content: welcomeMessage
  }
])

function scrollToBottom() {
  nextTick(() => {
    messageListRef.value?.scrollToBottom()
  })
}

async function handleSend() {
  const content = inputValue.value.trim()
  if (!content || loading.value) {
    return
  }

  messages.value.push({
    id: crypto.randomUUID(),
    role: 'user',
    content
  })

  inputValue.value = ''
  loading.value = true
  streamingStarted.value = false
  scrollToBottom()

  const assistantMessage = {
    id: crypto.randomUUID(),
    role: 'assistant',
    content: ''
  }
  messages.value.push(assistantMessage)
  const assistantMessageIndex = messages.value.length - 1
  scrollToBottom()

  try {
    await sendMessageStream({
      message: content,
      memoryId: defaultMemoryId,
      onChunk(chunk) {
        streamingStarted.value = true
        messages.value[assistantMessageIndex].content += chunk
        scrollToBottom()
      }
    })

    if (!messages.value[assistantMessageIndex].content.trim()) {
      messages.value[assistantMessageIndex].content = invalidReplyMessage
    }
  } catch (error) {
    const errorMessage =
      error?.message ||
      requestFailedFallback

    messages.value[assistantMessageIndex].content = `${requestFailedPrefix}${errorMessage}`
  } finally {
    loading.value = false
    streamingStarted.value = false
    scrollToBottom()
  }
}
</script>
