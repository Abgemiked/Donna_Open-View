"""Structured logging setup using structlog."""
from __future__ import annotations

import logging
import sys
from typing import Any

import structlog

_configured = False


def configure_logging(log_level: str = "INFO") -> None:
    """Configure structlog + stdlib logging once per process."""
    global _configured
    if _configured:
        return

    level = getattr(logging, log_level.upper(), logging.INFO)

    logging.basicConfig(
        format="%(message)s",
        stream=sys.stdout,
        level=level,
    )

    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            structlog.processors.StackInfoRenderer(),
            structlog.processors.format_exc_info,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(level),
        context_class=dict,
        logger_factory=structlog.PrintLoggerFactory(),
        cache_logger_on_first_use=True,
    )
    _configured = True


def get_logger(name: str = "assistent", **initial_context: Any) -> structlog.stdlib.BoundLogger:
    """Return a bound structlog logger."""
    if not _configured:
        configure_logging()
    return structlog.get_logger(name).bind(**initial_context)
