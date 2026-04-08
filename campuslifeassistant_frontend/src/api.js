import axios from 'axios'

const http = axios.create({
  baseURL: 'http://localhost:8088',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json'
  }
})

export function sendMessage(message) {
  return http.post('/chat', { message })
}
