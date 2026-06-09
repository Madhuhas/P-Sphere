import express, { Request, Response, NextFunction } from 'express';
import cors from 'cors';
import helmet from 'helmet';
import authRoutes     from './routes/auth.routes';
import deviceRoutes   from './routes/device.routes';
import syncRoutes     from './routes/sync.routes';
import photosRoutes   from './routes/photos.routes';
import aiRoutes       from './routes/ai.routes';
import albumsRoutes   from './routes/albums.routes';
import statsRoutes    from './routes/stats.routes';
import facesRoutes    from './routes/faces.routes';
import settingsRoutes from './routes/settings.routes';
import { config }     from './config/env';

const app = express();

app.use(helmet());

// In production, restrict CORS to an explicit origin.
// Set CORS_ORIGIN env var to the web UI's base URL (e.g. http://192.168.1.10:5173).
const corsOrigin = process.env.CORS_ORIGIN;
app.use(cors({
  origin: config.nodeEnv === 'production' && corsOrigin ? corsOrigin : true,
  credentials: true,
}));

// Limit JSON bodies to 1 MB to prevent memory exhaustion from oversized payloads.
// Multipart uploads bypass this and are governed by multer limits in storage config.
app.use(express.json({ limit: '1mb' }));

app.get('/api/health', (_req, res) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

app.use('/api/auth',     authRoutes);
app.use('/api/devices',  deviceRoutes);
app.use('/api/sync',     syncRoutes);
app.use('/api/photos',   photosRoutes);
app.use('/api/ai',       aiRoutes);
app.use('/api/albums',   albumsRoutes);
app.use('/api/stats',    statsRoutes);
app.use('/api/faces',    facesRoutes);
app.use('/api/settings', settingsRoutes);

// Catch-all error handler — prevents Express from leaking stack traces in production
// eslint-disable-next-line @typescript-eslint/no-unused-vars
app.use((err: Error, _req: Request, res: Response, _next: NextFunction) => {
  console.error('[app] Unhandled error:', err);
  res.status(500).json({ error: 'Internal server error' });
});

export default app;
