import { BrowserRouter, Routes, Route } from 'react-router-dom';
import Navigation from './components/Navigation';
import DashboardPage from './pages/DashboardPage';
import SyncPage from './pages/SyncPage';
import AdminPage from './pages/AdminPage';

export default function App() {
  return (
    <BrowserRouter>
      <div style={{ display:'flex', minHeight:'100vh', background:'#0f1923', fontFamily:'system-ui, sans-serif' }}>
        <Navigation />
        <main style={{ flex:1, overflowY:'auto' }}>
          <Routes>
            <Route path="/"      element={<DashboardPage />} />
            <Route path="/sync"  element={<SyncPage />} />
            <Route path="/admin" element={<AdminPage />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  );
}
