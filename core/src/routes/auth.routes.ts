import { Router, Request, Response } from 'express';
import { registerUser, loginUser } from '../services/auth.service';

const router = Router();

router.post('/register', async (req: Request, res: Response) => {
  const { email, password } = req.body;
  if (!email || !password) {
    res.status(400).json({ error: 'Email and password are required' });
    return;
  }
  try {
    const user = await registerUser(email, password);
    res.status(201).json({ user });
  } catch (err: any) {
    if (err.code === '23505') {
      res.status(409).json({ error: 'Email already registered' });
    } else {
      res.status(500).json({ error: 'Registration failed' });
    }
  }
});

router.post('/login', async (req: Request, res: Response) => {
  const { email, password } = req.body;
  if (!email || !password) {
    res.status(400).json({ error: 'Email and password are required' });
    return;
  }
  try {
    const result = await loginUser(email, password);
    res.json(result);
  } catch {
    res.status(401).json({ error: 'Invalid credentials' });
  }
});

export default router;
