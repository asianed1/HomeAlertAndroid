import express from 'express';
import fs from 'fs';
import path from 'path';
import JSZip from 'jszip';
import { createServer as createViteServer } from 'vite';

const PORT = Number(process.env.PORT || 3000);
const ANDROID_ROOT = path.resolve(process.cwd(), 'main');

interface AndroidFileEntry {
  path: string;
  content: string;
  size: number;
  language: string;
}

function detectLanguage(filePath: string): string {
  if (filePath.endsWith('.kt') || filePath.endsWith('.kts')) return 'kotlin';
  if (filePath.endsWith('.xml')) return 'xml';
  if (filePath.endsWith('.pro')) return 'proguard';
  if (filePath.endsWith('.toml')) return 'toml';
  if (filePath.endsWith('.sh')) return 'bash';
  if (filePath.endsWith('.md')) return 'markdown';
  return 'text';
}

function collectAndroidFiles(dir: string, baseDir: string): AndroidFileEntry[] {
  if (!fs.existsSync(dir)) return [];
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  const results: AndroidFileEntry[] = [];

  for (const entry of entries) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      results.push(...collectAndroidFiles(fullPath, baseDir));
    } else if (entry.isFile()) {
      const relPath = path.relative(baseDir, fullPath).replace(/\\/g, '/');
      const content = fs.readFileSync(fullPath, 'utf-8');
      results.push({
        path: relPath,
        content,
        size: Buffer.byteLength(content, 'utf-8'),
        language: detectLanguage(relPath),
      });
    }
  }

  return results.sort((a, b) => a.path.localeCompare(b.path));
}

async function startServer() {
  const app = express();
  app.use(express.json({ limit: '4mb' }));

  // Return full Android Studio Kotlin project tree & contents
  app.get('/api/android-files', (_req, res) => {
    try {
      const files = collectAndroidFiles(ANDROID_ROOT, ANDROID_ROOT);
      res.json({
        projectName: 'HomeAlertAndroid',
        fileCount: files.length,
        files,
      });
    } catch (err) {
      res.status(500).json({ error: (err as Error).message });
    }
  });

  // Bundle complete Android Gradle project into a downloadable .zip
  app.get('/api/android-project.zip', async (_req, res) => {
    try {
      const files = collectAndroidFiles(ANDROID_ROOT, ANDROID_ROOT);
      const zip = new JSZip();
      for (const file of files) {
        zip.file(`HomeAlertAndroid/${file.path}`, file.content);
      }
      const buffer = await zip.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE' });
      res.setHeader('Content-Type', 'application/zip');
      res.setHeader('Content-Disposition', 'attachment; filename="HomeAlertAndroid.zip"');
      res.send(buffer);
    } catch (err) {
      res.status(500).json({ error: (err as Error).message });
    }
  });

  // Real Telegram Bot API test/alert endpoint for the interactive simulator
  app.post('/api/telegram/send-photo', async (req, res) => {
    try {
      const { botToken, chatId, caption, imageBase64 } = req.body as {
        botToken?: string;
        chatId?: string;
        caption?: string;
        imageBase64?: string;
      };

      if (!botToken?.trim() || !chatId?.trim()) {
        res.status(400).json({ ok: false, error: 'Bot Token and Chat ID are required.' });
        return;
      }

      const cleanBase64 = (imageBase64 || '').replace(/^data:image\/\w+;base64,/, '');
      if (!cleanBase64) {
        const msgResp = await fetch(
          `https://api.telegram.org/bot${encodeURIComponent(botToken.trim())}/sendMessage`,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              chat_id: chatId.trim(),
              text: caption || 'HomeAlertAndroid Diagnostic Alert',
            }),
          }
        );
        const msgJson = await msgResp.json();
        res.status(msgResp.ok ? 200 : 502).json(msgJson);
        return;
      }

      const imageBuffer = Buffer.from(cleanBase64, 'base64');
      const formData = new FormData();
      formData.append('chat_id', chatId.trim());
      formData.append('caption', caption || 'HomeAlert Motion Snapshot');
      formData.append(
        'photo',
        new Blob([imageBuffer], { type: 'image/jpeg' }),
        'homealert_snapshot.jpg'
      );

      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 4500);
      try {
        const tgResp = await fetch(
          `https://api.telegram.org/bot${encodeURIComponent(botToken.trim())}/sendPhoto`,
          {
            method: 'POST',
            body: formData,
            signal: controller.signal,
          }
        );
        const tgJson = await tgResp.json();
        res.status(tgResp.ok ? 200 : 502).json(tgJson);
      } finally {
        clearTimeout(timeout);
      }
    } catch (err) {
      res.status(500).json({ ok: false, error: (err as Error).message });
    }
  });

  if (process.env.NODE_ENV !== 'production') {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distDir = path.resolve(process.cwd(), 'dist');
    app.use(express.static(distDir));
    app.get('*', (_req, res) => {
      res.sendFile(path.join(distDir, 'index.html'));
    });
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log(`HomeAlertAndroid Studio listening on http://0.0.0.0:${PORT}`);
  });
}

startServer();
