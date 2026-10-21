const express = require('express');
const { getPublicKey } = require('../services/tokenService');

const router = express.Router();

router.get('/public-key', (request, response) => {
  try {
    const publicKey = getPublicKey();
    response.type('text/plain').send(publicKey);
  } catch (error) {
    response.status(500).json({ error: 'Unable to load public key.' });
  }
});

module.exports = router;
