FROM node:alpine

WORKDIR /app

ADD ./package.json      ./
ADD ./package-lock.json ./
ADD ./tsconfig.json     ./
ADD ./src               ./src

RUN npm install

EXPOSE 3000

CMD ["npm", "start"]
