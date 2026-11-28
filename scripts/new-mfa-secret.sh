#!/usr/bin/env bash
# Prints a new MFA secret (Base32, 160 bits) for TRADING_PROD_MFA_SECRET and how to add it to an authenticator app.
# Nothing is stored: copy it into .env yourself. The label below is only what the app shows.
set -euo pipefail
secret=$(head -c 20 /dev/urandom | base32 | tr -d '=\n')
echo "TRADING_PROD_MFA_SECRET=${secret}"
echo
echo "In the authenticator app choose 'enter a setup key' and use:"
echo "  account: etf-trading-bot    key: ${secret}    type: time-based (6 digits, 30 s)"
echo "Or scan a QR made from this address (only on your own machine):"
echo "  otpauth://totp/etf-trading-bot?secret=${secret}&issuer=etf-trading-bot&digits=6&period=30"
