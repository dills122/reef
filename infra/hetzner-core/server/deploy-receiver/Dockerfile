FROM node:22-alpine

WORKDIR /app

COPY server.mjs /app/server.mjs

USER node

EXPOSE 8090

CMD ["node", "/app/server.mjs"]
