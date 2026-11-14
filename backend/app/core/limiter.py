"""Shared slowapi Limiter instance — importierbar von Routes und main.py."""
from __future__ import annotations

from slowapi import Limiter
from slowapi.util import get_remote_address

# Einzige Instanz — wird in main.py an app.state.limiter gehängt
# und von Route-Decorators via @limiter.limit(...) genutzt.
limiter = Limiter(key_func=get_remote_address)
