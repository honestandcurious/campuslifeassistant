<template>
  <main ref="containerRef" class="chat-messages">
    <div
      v-for="message in messages"
      :key="message.id"
      class="message-row"
      :class="message.role"
    >
      <div class="message-bubble">
        <div class="message-role">
          {{ message.role === 'user' ? userLabel : assistantLabel }}
        </div>
        <div class="message-text">{{ message.content }}</div>
      </div>
    </div>

    <div v-if="loading" class="message-row assistant">
      <div class="message-bubble thinking-bubble">
        <div class="message-role">{{ assistantLabel }}</div>
        <div class="message-text">{{ thinkingText }}</div>
      </div>
    </div>
  </main>
</template>

<script setup>
import { ref } from 'vue'

defineProps({
  messages: {
    type: Array,
    required: true
  },
  loading: {
    type: Boolean,
    default: false
  }
})

const containerRef = ref(null)
const userLabel = '\u4f60'
const assistantLabel = 'AI'
const thinkingText = 'AI\u6b63\u5728\u601d\u8003...'

function scrollToBottom() {
  if (!containerRef.value) {
    return
  }
  containerRef.value.scrollTop = containerRef.value.scrollHeight
}

defineExpose({
  scrollToBottom
})
</script>
