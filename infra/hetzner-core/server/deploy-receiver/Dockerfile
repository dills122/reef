FROM node:26-alpine

WORKDIR /app

COPY server.mjs /app/server.mjs

USER node

EXPOSE 8090

CMD ["node", "/app/server.mjs"]
