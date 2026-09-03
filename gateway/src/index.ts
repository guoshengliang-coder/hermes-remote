import { createGatewayRuntime } from "./gateway-runtime.js";

const runtime = createGatewayRuntime(process.env);
runtime.start();

process.once("SIGTERM", () => runtime.shutdown("SIGTERM"));
process.once("SIGINT", () => runtime.shutdown("SIGINT"));
