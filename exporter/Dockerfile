FROM arm64v8/node:8-alpine

WORKDIR /app

COPY ./package.json ./index.js ./

RUN apk add --update --no-cache --allow-untrusted --virtual .yarn yarn \
	&& yarn install \
	&& apk del .yarn

EXPOSE 9300

CMD ["npm", "start"]

