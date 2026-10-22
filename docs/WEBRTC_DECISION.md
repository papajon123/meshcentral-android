# Transport decision — 22 October 2026

Keep the existing WSS relay for 0.7.0. A permanent server fork is not required: the optional companion plugin uses the existing MeshCentral 1.3.0 hooks without modifying core.

The upstream browser protocol uses SDP and webrtc0/1/2/noTrickle messages. Agent core 4515 rejects the terminal RTC offer; a desktop data-channel implementation would need separate compatibility work and safe relay fallback.

A local-host protocol-5 WSS control-channel experiment made 50 round trips: p50 0.86 ms, p95 2.26 ms; five connection opens had a median 59.54 ms. This is host-to-local-Docker transport evidence, **not phone latency, desktop frame performance or a WSS/WebRTC comparison**.

Before changing transport, measure a signed build on a phone: LAN and mobile internet, NAT/TURN, p50/p95 interactive/frame latency, traffic, CPU, memory, battery and recovery. Include multiple monitors and input/accessibility. Retain WSS fallback. Keep this as the explicit next acceptance gate, not an implied implemented feature.
