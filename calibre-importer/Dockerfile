FROM lscr.io/linuxserver/calibre:latest

WORKDIR /app

COPY app.py /app/app.py
COPY grimmory_repair.py /app/grimmory_repair.py
COPY consolidation.py /app/consolidation.py
COPY templates /app/templates
COPY static /app/static

EXPOSE 8090

ENTRYPOINT ["python3", "/app/app.py"]
