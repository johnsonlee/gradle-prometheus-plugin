import Koa from 'koa';
import KoaRouter from 'koa-router';
import * as prometheus from 'prom-client';
import bodyParser from 'koa-bodyparser';

const registry = new prometheus.Registry();
const metrics: { [key: string]: prometheus.Gauge<any> } = [{
    name: 'gradle_settings_duration_ms',
    help: 'The duration of project settings evaluation',
    labelNames: ['project'],
}, {
    name: 'gradle_project_evaluation_duration_ms',
    help: 'The duration of project evaluation',
    labelNames: ['project', 'path', 'executed', 'status'],
}, {
    name: 'gradle_task_execution_duration_ms',
    help: 'The duration of task execution',
    labelNames: ['project', 'path', 'status', 'didWork', 'executed', 'noSource', 'skipped', 'skipMessage', 'upToDate'],
}, {
    name: 'gradle_build_duration_ms',
    help: 'Gradle build duration',
    labelNames: ['project', 'tasks', 'status'],
}].map(it => {
    const metric = new prometheus.Gauge(it);
    registry.registerMetric(metric);
    return {
        name: it.name,
        metric
    }
}).reduce((acc: any, v) => {
    acc[v.name] = v.metric;
    return acc;
}, {});

const router = new KoaRouter()
    .get('/metrics', async (ctx, _next) => {
        ctx.body = await registry.metrics();
    })
    .post('/metrics', async (ctx, _next) => {
        const body = ctx.request.body
        if (!body || !body.metric || !body.labels) {
            console.log("error: #1")
            ctx.status = 400;
            return;
        }

        const metric = metrics[body.metric];
        if (!metric) {
            console.log("error: #2")
            ctx.status = 400;
            return;
        }

        metric.labels(body.labels).set(body.value || 0);
        ctx.body = ctx.request.body;
    });

new Koa()
    .use(bodyParser({
        enableTypes: ['json'],
        onerror: function (err, ctx) {
            console.log(err);
            ctx.throw('Bad Request', 400);
        }
    }))
    .use(router.routes())
    .listen(3000);
