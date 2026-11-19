"""Railflag feature flags for Python: an OpenFeature provider that evaluates locally."""

__version__ = "0.4.0"

from .evaluate import EvalResult, evaluate, evaluate_all  # noqa: E402
from .provider import RailflagProvider  # noqa: E402
from .source import ConfigSource  # noqa: E402

__all__ = ["ConfigSource", "EvalResult", "RailflagProvider", "evaluate", "evaluate_all", "__version__"]
