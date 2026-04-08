<template>
  <footer class="chat-input-wrapper">
    <div class="chat-input-box">
      <textarea
        :value="modelValue"
        class="chat-input"
        :placeholder="placeholder"
        :disabled="loading"
        rows="1"
        @input="handleInput"
        @keydown="handleKeydown"
      />
      <button
        class="send-button"
        :disabled="loading || !modelValue.trim()"
        @click="$emit('send')"
      >
        {{ loading ? sendingText : sendText }}
      </button>
    </div>
  </footer>
</template>

<script setup>
const props = defineProps({
  modelValue: {
    type: String,
    default: ''
  },
  loading: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:modelValue', 'send'])
const placeholder =
  '\u8f93\u5165\u4f60\u7684\u95ee\u9898\uff0c\u6309\u56de\u8f66\u53d1\u9001'
const sendText = '\u53d1\u9001'
const sendingText = '\u53d1\u9001\u4e2d...'

function handleInput(event) {
  emit('update:modelValue', event.target.value)
}

function handleKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    if (!props.loading && props.modelValue.trim()) {
      emit('send')
    }
  }
}
</script>
