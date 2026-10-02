# frozen_string_literal: true
# Appended to every generated Graal route/group unit.
# common.rb and this unit source are each evaluated exactly once in a long-lived Context.

if defined?(ores_gs_http)
  OresApp::GraalCapabilities.http = ores_gs_http
end

module OresGenerated
  module GraalEntrypoint
    module_function

    def call(request)
      OresApp::ThreadStateBoundary.call do
        OresApp::Dispatcher.call(request, routes: OresGenerated::Routes::TABLE, invoker: method(:invoke))
      end
    end
  end
end

def ores_graal_invoke(request_json)
  raise "Graal host HTTP capability is unavailable" unless OresApp::GraalCapabilities.http

  request = JSON.parse(request_json.to_s)
  JSON.generate(OresGenerated::GraalEntrypoint.call(request))
end

request_invoker = method(:ores_graal_invoke)

->(*args) do
  if args.length == 1 && args.first.is_a?(String)
    request_invoker.call(args.first)
  else
    OresApp::GraalCapabilities.http ||= args.fetch(0)
    request_invoker
  end
end
