#!/usr/bin/env ruby
# Minimal trusted local HTTP Data API used only by the demo/local compose stack.
require "json"
require "socket"

host = ENV.fetch("DATA_API_BIND_HOST", "127.0.0.1")
port = Integer(ENV.fetch("DATA_API_PORT", "8787"))
server = TCPServer.new(host, port)
warn "mock HTTP Data API listening on http://#{host}:#{port}"

loop do
  socket = server.accept
  begin
    request_line = socket.gets
    next unless request_line
    method, target, = request_line.split(" ", 3)
    headers = {}
    while (line = socket.gets)
      line = line.chomp
      break if line.empty?
      name, value = line.split(":", 2)
      headers[name.downcase] = value.to_s.strip
    end
    length = Integer(headers.fetch("content-length", "0"))
    raw_body = length.positive? ? socket.read(length) : ""
    parsed_body = raw_body.empty? ? nil : JSON.parse(raw_body) rescue raw_body

    payload = if target.start_with?("/healthz")
      { ok: true, service: "mock-data-api" }
    else
      { ok: true, method: method, target: target, body: parsed_body }
    end
    body = JSON.generate(payload)
    socket.write("HTTP/1.1 200 OK\r\n")
    socket.write("Content-Type: application/json\r\n")
    socket.write("Content-Length: #{body.bytesize}\r\n")
    socket.write("Connection: close\r\n\r\n")
    socket.write(body)
  rescue StandardError => error
    warn "mock data api error: #{error.class}: #{error.message}"
  ensure
    socket.close rescue nil
  end
end
