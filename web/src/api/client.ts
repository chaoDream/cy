import axios from 'axios'

const client = axios.create({
  baseURL: '/',
  timeout: 30000,
})

client.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body.code !== 0) {
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body.data
  },
  (err) => {
    return Promise.reject(new Error(err.message || '网络异常'))
  }
)

export default client
