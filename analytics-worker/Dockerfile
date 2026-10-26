FROM golang:1.27 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /analytics-worker .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /analytics-worker /analytics-worker
EXPOSE 9100
ENTRYPOINT ["/analytics-worker"]
