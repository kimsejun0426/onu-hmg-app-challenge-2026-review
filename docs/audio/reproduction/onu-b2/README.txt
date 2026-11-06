오누 B2 음원 재현

앱에는 최종 음원이 포함되므로 앱 빌드에 음성 모델이나 GPU가 필요하지 않습니다.
재합성은 별도 Python/CUDA 환경을 사용합니다. 제작 환경의 버전은 다음과 같습니다.
qwen-tts 0.1.1, torch 2.7.1+cu128, transformers 4.57.3, numpy 2.5.4,
soundfile 0.14.0, accelerate 1.12.0, huggingface-hub 0.36.2, FFmpeg 7.1.1.

모델: Qwen/Qwen3-TTS-12Hz-1.7B-Base
고정 revision: fd4b254389122332181a7c3db7f27e918eec64e3
모델을 해당 revision으로 별도 준비하고 model-dir/download-manifest.json에
{"revision":"fd4b254389122332181a7c3db7f27e918eec64e3"}를 기록합니다.
이 필드는 준비한 판본의 선언이며 가중치 파일 전체에 대한 무결성 증명은 아닙니다.
모델과 합성용 venv는 제출 소스에 포함하지 않습니다.

저장소 루트에서 tools/build_qwen_voice.py --help로 인자를 확인합니다.
--model-dir, --plan docs/audio/onu-0.47.0-voice-plan.json,
--reference docs/audio/reproduction/onu-b2/reference.wav,
--output 새 절대 경로, --ffmpeg 실행 파일,
--cue-source apps/vehicle/app/src/main/assets/story_audio, --execute를 지정합니다.
도구는 로컬 파일만 사용하며 온라인 모델 다운로드를 하지 않습니다.

B2 참조는 자체 생성한 가상 음색입니다. 사람의 녹음을 사용하지 않았습니다.
모델·참조 출처는 REFERENCE.json, 실제 제작·선정 기록은 voice-provenance.json을 따릅니다.
음량만 조정했으며 피치·속도를 가공하지 않았습니다. GPU·라이브러리 차이로 WAV 바이트가
달라질 수 있으므로 대본·문장 끝·신호·사람 청취 검토를 다시 수행해야 합니다.
자동 전사 일치는 자연스러움이나 아이의 이해도 인증이 아닙니다.
