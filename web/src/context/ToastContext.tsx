import { createContext, useCallback, useContext, useState } from 'react';

type ToastType = 'info' | 'success' | 'error';
interface Toast { id: number; message: string; type: ToastType }
interface ToastCtx { show: (message: string, type?: ToastType) => void }

const Ctx = createContext<ToastCtx>({ show: () => {} });

let nextId = 0;

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const show = useCallback((message: string, type: ToastType = 'info') => {
    const id = ++nextId;
    setToasts((prev) => [...prev, { id, message, type }]);
    setTimeout(() => setToasts((prev) => prev.filter((t) => t.id !== id)), 3500);
  }, []);

  const colors: Record<ToastType, string> = {
    info:    'var(--primary)',
    success: '#2e7d32',
    error:   'var(--danger)',
  };

  return (
    <Ctx.Provider value={{ show }}>
      {children}
      <div style={{ position: 'fixed', bottom: 24, right: 24, display: 'flex', flexDirection: 'column', gap: 10, zIndex: 9999 }}>
        {toasts.map((t) => (
          <div key={t.id} style={{
            background: colors[t.type], color: '#fff', padding: '12px 20px',
            borderRadius: 10, fontSize: 14, fontWeight: 500, maxWidth: 320,
            boxShadow: '0 4px 20px rgba(0,0,0,.4)',
            animation: 'fadeSlideIn .2s ease both',
          }}>
            {t.message}
          </div>
        ))}
      </div>
    </Ctx.Provider>
  );
}

export const useToast = () => useContext(Ctx);
