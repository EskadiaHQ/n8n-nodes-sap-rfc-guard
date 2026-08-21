FROM node:24.18.0-alpine

WORKDIR /app
COPY --chown=node:node server.mjs /app/server.mjs

ENV PORT=8080
USER node

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:8080/v1/health',{headers:{authorization:'Bearer '+process.env.CONTRACT_TOKEN}}).then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"

CMD ["node", "/app/server.mjs"]
