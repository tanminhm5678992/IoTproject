/**
 * API client – gắn JWT tự động, xử lý 401 (tự đăng xuất).
 * Đặc tả mục 9: mọi request (trừ /api/auth/login) cần header Authorization.
 */
import axios from 'axios';

// Base URL: khi dev dùng proxy của Vite, production dùng biến VITE_API_URL
const API_BASE = import.meta.env.VITE_API_URL || '';

const client = axios.create({
  baseURL: API_BASE,
  headers: { 'Content-Type': 'application/json' },
});

// === Quản lý token trong bộ nhớ ===
let _token = localStorage.getItem('jwt') || null;

export function getToken() { return _token; }

export function setToken(token) {
  _token = token;
  if (token) localStorage.setItem('jwt', token);
  else localStorage.removeItem('jwt');
}

export function isLoggedIn() { return !!_token; }

// === Interceptor: gắn JWT trước mỗi request ===
client.interceptors.request.use((config) => {
  if (_token) {
    config.headers.Authorization = `Bearer ${_token}`;
  }
  return config;
});

// === Interceptor: xử lý 401 → tự đăng xuất ===
client.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      setToken(null);
      // Chuyển hướng về trang login (trừ khi đang ở login)
      if (!window.location.pathname.includes('/login')) {
        window.location.href = '/login';
      }
    }
    return Promise.reject(err);
  }
);

// === Auth API ===
export async function login(username, password) {
  const res = await client.post('/api/auth/login', { username, password });
  setToken(res.data.token);
  return res.data;
}

export function logout() {
  setToken(null);
}

// === Device API ===
export async function getDevices() {
  const res = await client.get('/api/devices');
  return res.data;
}

export async function getDevice(deviceId) {
  const res = await client.get(`/api/devices/${deviceId}`);
  return res.data;
}

export async function createDevice(data) {
  const res = await client.post('/api/devices', data);
  return res.data;
}

export async function deleteDevice(deviceId) {
  await client.delete(`/api/devices/${deviceId}`);
}

// === Telemetry API ===
export async function getTelemetry(deviceId, params = {}) {
  const res = await client.get(`/api/devices/${deviceId}/telemetry`, { params });
  return res.data;
}

export async function getLatestTelemetry(deviceId) {
  const res = await client.get(`/api/devices/${deviceId}/telemetry/latest`);
  return res.data;
}

// === Command API ===
export async function sendCommand(deviceId, target, value) {
  const res = await client.post(`/api/devices/${deviceId}/commands`, { target, value });
  return res.data;
}

export async function getCommands(deviceId, params = {}) {
  const res = await client.get(`/api/devices/${deviceId}/commands`, { params });
  return res.data;
}

export default client;
