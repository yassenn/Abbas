FROM node:20-alpine

ENV NODE_ENV=production
WORKDIR /app

# Install production deps first (better layer caching); lockfile is committed.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY src ./src

# Run as the unprivileged user that ships with the base image.
USER node

EXPOSE 8080
CMD ["node", "src/server.js"]
