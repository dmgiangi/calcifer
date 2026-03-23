package main

import (
	"context"
	"fmt"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetricgrpc"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/propagation"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
	"go.opentelemetry.io/otel/trace"
)

const serviceName = "calcifer-auth"

// Telemetry holds OTel providers and instruments.
type Telemetry struct {
	tracerProvider *sdktrace.TracerProvider
	meterProvider  *sdkmetric.MeterProvider
	Tracer         trace.Tracer

	// Metric instruments
	RequestsTotal   metric.Int64Counter
	AuthorizedTotal metric.Int64Counter
	DeniedTotal     metric.Int64Counter
	LoginRedirects  metric.Int64Counter
	CallbacksTotal  metric.Int64Counter
	RequestDuration metric.Float64Histogram
}

// InitTelemetry sets up OTel trace and metric providers with OTLP gRPC export.
func InitTelemetry(ctx context.Context, endpoint string) (*Telemetry, error) {
	res, err := resource.New(ctx,
		resource.WithAttributes(semconv.ServiceName(serviceName)),
	)
	if err != nil {
		return nil, fmt.Errorf("resource: %w", err)
	}

	// Trace exporter
	traceExp, err := otlptracegrpc.New(ctx,
		otlptracegrpc.WithEndpoint(endpoint),
		otlptracegrpc.WithInsecure(),
	)
	if err != nil {
		return nil, fmt.Errorf("trace exporter: %w", err)
	}

	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(traceExp),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.TraceContext{})

	// Metric exporter
	metricExp, err := otlpmetricgrpc.New(ctx,
		otlpmetricgrpc.WithEndpoint(endpoint),
		otlpmetricgrpc.WithInsecure(),
	)
	if err != nil {
		return nil, fmt.Errorf("metric exporter: %w", err)
	}

	mp := sdkmetric.NewMeterProvider(
		sdkmetric.WithReader(sdkmetric.NewPeriodicReader(metricExp,
			sdkmetric.WithInterval(15*time.Second),
		)),
		sdkmetric.WithResource(res),
	)
	otel.SetMeterProvider(mp)

	meter := mp.Meter(serviceName)
	tracer := tp.Tracer(serviceName)

	reqTotal, _ := meter.Int64Counter("calcifer_auth.requests",
		metric.WithDescription("Total forwarded auth requests"))
	authTotal, _ := meter.Int64Counter("calcifer_auth.authorized",
		metric.WithDescription("Authorized requests"))
	deniedTotal, _ := meter.Int64Counter("calcifer_auth.denied",
		metric.WithDescription("Denied requests (forbidden)"))
	loginRedirects, _ := meter.Int64Counter("calcifer_auth.login_redirects",
		metric.WithDescription("Redirects to Keycloak login"))
	callbacksTotal, _ := meter.Int64Counter("calcifer_auth.callbacks",
		metric.WithDescription("OAuth callbacks processed"))
	reqDuration, _ := meter.Float64Histogram("calcifer_auth.request_duration_ms",
		metric.WithDescription("Request processing duration in milliseconds"))

	return &Telemetry{
		tracerProvider:  tp,
		meterProvider:   mp,
		Tracer:          tracer,
		RequestsTotal:   reqTotal,
		AuthorizedTotal: authTotal,
		DeniedTotal:     deniedTotal,
		LoginRedirects:  loginRedirects,
		CallbacksTotal:  callbacksTotal,
		RequestDuration: reqDuration,
	}, nil
}

// Shutdown flushes and shuts down OTel providers.
func (t *Telemetry) Shutdown(ctx context.Context) {
	if t.tracerProvider != nil {
		_ = t.tracerProvider.Shutdown(ctx)
	}
	if t.meterProvider != nil {
		_ = t.meterProvider.Shutdown(ctx)
	}
}

