Pod::Spec.new do |s|
  s.name           = 'InstagramShare'
  s.version        = '1.0.0'
  s.summary        = '인스타그램 스토리에 스티커를 얹어 넘기는 로컬 모듈'
  s.description    = '스토리 편집기를 열면서 사용자가 옮길 수 있는 스티커 한 장을 함께 건넨다.'
  s.author         = 'VictoryFairy'
  s.homepage       = 'https://victoryfairy.com'
  s.platforms      = {
    :ios => '16.4',
    :tvos => '16.4'
  }
  s.source         = { git: '' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  # Swift/Objective-C compatibility
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
  }

  s.source_files = "**/*.{h,m,mm,swift,hpp,cpp}"
end
