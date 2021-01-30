const Koa = require('koa');
const redis = require('redis');
const { promisify } = require("util");
const bluebird = require('bluebird');
const bodyParser = require('koa-bodyparser');

bluebird.promisifyAll(redis.RedisClient.prototype);

const app = new Koa();
const client = redis.createClient({
    host: process.env.REDIS_HOST || '127.0.0.1',
    port: process.env.REDIS_PORT || 6379,
}).on('error', e => {
    console.error(e);
});

app.use(bodyParser({
    enableTypes: ["text"],
    onerror: (e, ctx) => {
        ctx.throw(400, e);
    },
})).use(async (ctx, next) => {
    if (/^POST$/i.test(ctx.request.method) && !ctx.is("text/*")) {
        ctx.throw(400);
        return;
    }
    await next();
}).use(async ctx => {
    console.log(`${ctx.request.method} ${ctx.request.url}`);

    if (/^POST$/i.test(ctx.request.method)) {
        await client.setAsync(process.env.REDIS_KEY, ctx.request.body).then(reply => {
            ctx.body = {
                message: reply
            };
        }).catch(e => {
            ctx.throw(e);
        });
    } else {
        ctx.body = (await client.getAsync(process.env.REDIS_KEY)) || "# no metrics data";
    }
});

app.listen(9300, () => {
    console.log("Listen on 9300");
});
