Pod::Spec.new do |s|
  s.name           = 'StickerKeyboard'
  s.version        = '1.0.0'
  s.summary        = '승요 스티커 키보드가 쓸 스티커를 받아 두는 로컬 모듈'
  s.description    = '웹이 담은 스티커를 App Group 폴더에 받아 두고, 키보드 상태와 인스타그램·설정 열기를 맡는다.'
  s.author         = 'VictoryFairy'
  s.homepage       = 'https://victoryfairy.com'
  s.platforms      = {
    :ios => '16.4'
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
