# Reenable Telegram for existing Hermes Agent Gateway

Observed: Hermes Gateway is running but logs say No messaging platforms enabled. Commander is also running, and worker concurrency is throttled by system memory pressure.

This helper does not create a new bot or change other services. It does not display credentials or Telegram user IDs.

Read-only diagnostic:
    bash scripts/hermes-telegram-activate.sh --check

Interactive setup of existing bot:
    bash scripts/hermes-telegram-activate.sh --setup

The interactive mode backs up the private Hermes environment file with restrictive permissions, launches the official Hermes gateway setup wizard, asks the operator to select Telegram and reuse the existing bot, then restarts only Hermes Gateway. The owner enters the existing bot token and numeric allowed user ID directly in the local terminal. NEVER share tokens or user IDs in chat/screenshots.

Check from your phone by sending a private test message to that bot and confirm a genuine reply. The script does not connect CEO agents: first integrate the signed control adapter from AI Company PR #15, then validate actual Telegram sender metadata, configured model budgets, worker memory and independent reviews.

Do not change the temporary HERMES_NODE_TOKEN or alter Commander as part of this Telegram repair. Fix its persistence separately.

Official documentation: https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/messaging/telegram.md
Gateway reference: https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/messaging/index.md