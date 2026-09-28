"""Validasi access token Supabase. Identitas pengguna hanya berasal dari klaim `sub`."""

import uuid
from functools import lru_cache
from typing import Annotated

import jwt
from fastapi import Depends, Request

from app.api.errors import ApiError
from app.core.config import Settings, get_settings

_ASYMMETRIC_ALGS = ("ES256", "RS256")


@lru_cache(maxsize=4)
def _jwks_client(url: str) -> jwt.PyJWKClient:
    return jwt.PyJWKClient(url, cache_keys=True, lifespan=600)


def _unauthorized(message: str = "Sesi tidak valid atau kedaluwarsa") -> ApiError:
    return ApiError(401, "unauthorized", message)


def verify_access_token(token: str, settings: Settings) -> uuid.UUID:
    if not settings.jwt_issuer:
        raise ApiError(503, "service_unavailable", "Autentikasi belum dikonfigurasi di server ini")
    try:
        alg = jwt.get_unverified_header(token).get("alg")
        if alg == "HS256":
            if settings.jwt_hs256_secret is None:
                raise _unauthorized()
            key = settings.jwt_hs256_secret.get_secret_value()
        elif alg in _ASYMMETRIC_ALGS:
            key = _jwks_client(settings.jwks_url).get_signing_key_from_jwt(token).key
        else:
            raise _unauthorized()
        claims = jwt.decode(
            token,
            key,
            algorithms=[alg],
            audience=settings.jwt_audience,
            issuer=settings.jwt_issuer,
            options={"require": ["exp", "sub", "iss", "aud"]},
            leeway=30,
        )
        return uuid.UUID(claims["sub"])
    except ApiError:
        raise
    except (jwt.PyJWTError, ValueError, KeyError):
        raise _unauthorized() from None


def get_current_user_id(
    request: Request, settings: Annotated[Settings, Depends(get_settings)]
) -> uuid.UUID:
    header = request.headers.get("Authorization", "")
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise _unauthorized("Header Authorization Bearer diperlukan")
    return verify_access_token(token.strip(), settings)


CurrentUser = Annotated[uuid.UUID, Depends(get_current_user_id)]
