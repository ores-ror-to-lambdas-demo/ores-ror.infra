require "json"
require "net/http"
require "uri"
require_relative "adapter"

module OresRuntime
  module AwsLambdaRuntime
    API_VERSION = "2018-06-01".freeze

    module_function

    def run
      runtime_api = ENV.fetch("AWS_LAMBDA_RUNTIME_API")
      base = URI("http://#{runtime_api}")

      loop do
        invocation = runtime_request(base, "/#{API_VERSION}/runtime/invocation/next", Net::HTTP::Get)
        request_id = invocation.fetch_header("lambda-runtime-aws-request-id")
        trace_id = invocation.header("lambda-runtime-trace-id")
        ENV["_X_AMZN_TRACE_ID"] = trace_id if trace_id && !trace_id.empty?

        begin
          event = JSON.parse(invocation.body)
          response = OresRuntime::AwsLambda.handle(event, request_id: request_id)
          post_json(base, "/#{API_VERSION}/runtime/invocation/#{escape_path(request_id)}/response", response)
        rescue Exception => error # rubocop:disable Lint/RescueException
          payload = {
            "errorMessage" => error.message.to_s,
            "errorType" => error.class.name,
            "stackTrace" => Array(error.backtrace).first(20)
          }
          post_json(base, "/#{API_VERSION}/runtime/invocation/#{escape_path(request_id)}/error", payload,
            "lambda-runtime-function-error-type" => error.class.name)
        end
      end
    end

    def runtime_request(base, path, request_class)
      uri = base + path
      request = request_class.new(uri.request_uri)
      Net::HTTP.start(uri.host, uri.port, open_timeout: 2, read_timeout: 900) do |http|
        response = http.request(request)
        raise "Lambda Runtime API returned #{response.code}" unless response.is_a?(Net::HTTPSuccess)
        RuntimeResponse.new(response)
      end
    end

    def post_json(base, path, payload, headers = {})
      uri = base + path
      request = Net::HTTP::Post.new(uri.request_uri)
      request["content-type"] = "application/json"
      headers.each { |name, value| request[name] = value }
      request.body = JSON.generate(payload)
      Net::HTTP.start(uri.host, uri.port, open_timeout: 2, read_timeout: 30) do |http|
        response = http.request(request)
        raise "Lambda Runtime API returned #{response.code}" unless response.is_a?(Net::HTTPSuccess)
      end
    end

    def escape_path(value)
      URI.encode_www_form_component(value.to_s)
    end

    class RuntimeResponse
      attr_reader :body

      def initialize(response)
        @response = response
        @body = response.body.to_s
      end

      def header(name)
        @response[name]
      end

      def fetch_header(name)
        value = header(name)
        raise "Lambda Runtime API omitted #{name}" if value.nil? || value.empty?
        value
      end
    end
  end
end

OresRuntime::AwsLambdaRuntime.run if $PROGRAM_NAME == __FILE__
