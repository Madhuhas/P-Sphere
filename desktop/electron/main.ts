import { app, BrowserWindow, ipcMain, dialog, shell } from 'electron';
import path from 'path';
import { fileURLToPath } from 'url';
import { syncFolder } from './syncService';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const isDev = process.env.NODE_ENV === 'development';

let mainWindow: BrowserWindow | null = null;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 900,
    minHeight: 600,
    title: 'P-Sphere',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  if (isDev) {
    mainWindow.loadURL('http://localhost:5173');
    mainWindow.webContents.openDevTools();
  } else {
    mainWindow.loadFile(path.join(__dirname, '../renderer/index.html'));
  }

  mainWindow.on('closed', () => { mainWindow = null; });
}

app.whenReady().then(createWindow);

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) createWindow();
});

// ── IPC handlers ──────────────────────────────────────────────────────────────

ipcMain.handle('select-folder', async () => {
  if (!mainWindow) return null;
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ['openDirectory'],
    title: 'Select folder to sync',
  });
  return result.canceled ? null : result.filePaths[0];
});

ipcMain.handle('open-external', (_event, url: string) => {
  shell.openExternal(url);
});

ipcMain.handle('get-app-version', () => app.getVersion());

ipcMain.handle(
  'sync-folder-start',
  async (_event, { folder, baseUrl, jwt, deviceToken }: {
    folder: string; baseUrl: string; jwt: string; deviceToken: string;
  }) => {
    if (!mainWindow) return;
    await syncFolder(folder, baseUrl, jwt, deviceToken, mainWindow);
  }
);
