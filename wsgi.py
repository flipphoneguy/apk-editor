"""Gunicorn entry point:  gunicorn --workers 3 --timeout 120 wsgi:app"""
from app import create_app

app = create_app()
