/**
 * Hook useStomp – kết nối WebSocket STOMP tới /ws, tự nối lại khi đứt.
 * Đặc tả mục 9: endpoint /ws, xác thực JWT trong frame STOMP CONNECT.
 *
 * Dùng: const { subscribe, connected } = useStomp();
 *       useEffect(() => subscribe('/topic/telemetry/node1', (msg) => {...}), []);
 */
import { useEffect, useRef, useState, useCallback } from 'react';
import { Client } from '@stomp/stompjs';
import { getToken } from '../api/client';

// Tính WebSocket URL dựa trên cấu hình hiện tại
function getWsUrl() {
  const apiUrl = import.meta.env.VITE_API_URL;
  if (apiUrl) {
    // Production: VITE_API_URL=http://host:port → ws://host:port/ws
    const url = new URL(apiUrl);
    const protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${url.host}/ws`;
  }
  // Dev: proxy qua Vite → ws://localhost:5173/ws
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/ws`;
}

export default function useStomp() {
  const clientRef = useRef(null);
  const [connected, setConnected] = useState(false);
  // Lưu danh sách subscription callbacks để re-subscribe khi nối lại
  const subsRef = useRef(new Map());
  const stompSubsRef = useRef(new Map());

  useEffect(() => {
    const token = getToken();
    if (!token) return;

    const stompClient = new Client({
      brokerURL: getWsUrl(),
      connectHeaders: {
        Authorization: `Bearer ${token}`,
      },
      // Tự nối lại khi mất kết nối (delay tăng dần)
      reconnectDelay: 3000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,

      onConnect: () => {
        setConnected(true);
        // Re-subscribe tất cả topic khi nối lại
        subsRef.current.forEach((callback, destination) => {
          const sub = stompClient.subscribe(destination, (message) => {
            try {
              const data = JSON.parse(message.body);
              callback(data);
            } catch {
              callback(message.body);
            }
          });
          stompSubsRef.current.set(destination, sub);
        });
      },

      onDisconnect: () => {
        setConnected(false);
      },

      onStompError: (frame) => {
        console.error('[STOMP] Lỗi:', frame.headers?.message || frame.body);
        setConnected(false);
      },

      onWebSocketClose: () => {
        setConnected(false);
      },
    });

    clientRef.current = stompClient;
    stompClient.activate();

    return () => {
      stompClient.deactivate();
      stompSubsRef.current.clear();
      setConnected(false);
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  /**
   * Đăng ký lắng nghe một topic. Trả về hàm unsubscribe.
   * @param {string} destination - vd: '/topic/telemetry/node1'
   * @param {Function} callback - nhận dữ liệu JSON đã parse
   */
  const subscribe = useCallback((destination, callback) => {
    subsRef.current.set(destination, callback);

    // Nếu đã kết nối thì subscribe ngay
    if (clientRef.current?.connected) {
      const sub = clientRef.current.subscribe(destination, (message) => {
        try {
          const data = JSON.parse(message.body);
          callback(data);
        } catch {
          callback(message.body);
        }
      });
      stompSubsRef.current.set(destination, sub);
    }

    // Trả về hàm unsubscribe
    return () => {
      subsRef.current.delete(destination);
      const sub = stompSubsRef.current.get(destination);
      if (sub) {
        sub.unsubscribe();
        stompSubsRef.current.delete(destination);
      }
    };
  }, []);

  return { subscribe, connected };
}
