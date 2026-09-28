const fs = require('node:fs');
const path = require('node:path');
const jwt = require('jsonwebtoken');

const DEFAULT_PRIVATE_KEY_PATH = path.resolve(__dirname, '..', 'config', 'keys', 'jwtRS256.key');
const DEFAULT_PUBLIC_KEY_PATH = path.resolve(__dirname, '..', 'config', 'keys', 'jwtRS256.key.pub');
const DEFAULT_EXPIRES_IN = '15m';

function resolveKeyPath(configuredPath, fallbackPath) {
  if (!configuredPath) {
    return fallbackPath;
  }

  return path.isAbsolute(configuredPath)
    ? configuredPath
    : path.resolve(__dirname, '..', '..', configuredPath);
}

function loadPrivateKey() {
  const privateKeyPath = resolveKeyPath(process.env.PRIVATE_KEY_PATH, DEFAULT_PRIVATE_KEY_PATH);
  return fs.readFileSync(privateKeyPath, 'utf8');
}

function getPublicKey() {
  const publicKeyPath = resolveKeyPath(process.env.PUBLIC_KEY_PATH, DEFAULT_PUBLIC_KEY_PATH);
  return fs.readFileSync(publicKeyPath, 'utf8');
}

function generateAccessToken(user) {
  if (!user || !user.roleCategory) {
    throw new Error('roleCategory is required to generate an access token.');
  }

  const privateKey = loadPrivateKey();
  const expiresIn = process.env.JWT_EXPIRES_IN || DEFAULT_EXPIRES_IN;

  const payload = {
    roleCategory: user.roleCategory,
  };

  const signOptions = {
    algorithm: 'RS256',
    expiresIn,
  };

  const subject = user.id || user._id;
  if (subject) {
    signOptions.subject = String(subject);
  }

  return jwt.sign(payload, privateKey, signOptions);
}

module.exports = {
  generateAccessToken,
  getPublicKey,
};
