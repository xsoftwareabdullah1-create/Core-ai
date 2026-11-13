//! Deterministic, local intent classification. This module never executes actions.
//! Device operations remain behind Android's explicit approval service.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Intent { Build, DeviceAction, CodeGeneration, Workspace, Screen, Voice, Settings, Help, General }

impl Intent {
    pub fn label(self) -> &'static str {
        match self {
            Self::Build => "BUILD",
            Self::DeviceAction => "DEVICE_ACTION_REQUIRES_APPROVAL",
            Self::CodeGeneration => "CODE_GENERATION",
            Self::Workspace => "WORKSPACE",
            Self::Screen => "SCREEN_UNDERSTANDING",
            Self::Voice => "VOICE",
            Self::Settings => "SETTINGS",
            Self::Help => "HELP",
            Self::General => "GENERAL_ASSISTANCE",
        }
    }
    pub fn requires_approval(self) -> bool {
        matches!(self, Self::Build | Self::DeviceAction | Self::Workspace)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct IntentResult {
    pub intent: Intent,
    pub score: u32,
    pub matched_terms: Vec<&'static str>,
    pub approval_required: bool,
}

struct Rule { intent: Intent, terms: &'static [&'static str], weight: u32 }

const RULES: &[Rule] = &[
    Rule { intent: Intent::Build, weight: 5, terms: &["build apk","compile apk","assemble release","gradle build","make apk","apk"] },
    Rule { intent: Intent::DeviceAction, weight: 4, terms: &["tap","click","swipe","scroll","press back","press home","open app","launch app","type text","turn on","turn off"] },
    Rule { intent: Intent::CodeGeneration, weight: 3, terms: &["write code","generate code","create app","create game","implement","function","class","kotlin","python","rust","cpp","javascript"] },
    Rule { intent: Intent::Workspace, weight: 4, terms: &["create file","delete file","read file","list files","make folder","create folder","rename file","project folder"] },
    Rule { intent: Intent::Screen, weight: 4, terms: &["what is on screen","read screen","describe screen","take screenshot","screen content","visible text"] },
    Rule { intent: Intent::Voice, weight: 3, terms: &["listen","speak","voice","microphone","say aloud","text to speech","speech to text"] },
    Rule { intent: Intent::Settings, weight: 2, terms: &["settings","configure","preference","theme","dark mode","language"] },
    Rule { intent: Intent::Help, weight: 1, terms: &["help","how do i","how to","explain","what does"] },
];

fn normalize(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let mut space = true;
    for ch in input.chars().flat_map(char::to_lowercase) {
        if ch.is_alphanumeric() || ch == '_' || ch == '+' || ch == '#' {
            out.push(ch);
            space = false;
        } else if !space {
            out.push(' ');
            space = true;
        }
    }
    out.trim().to_owned()
}

fn matches_term(text: &str, term: &str) -> bool {
    if term.contains(' ') { text.contains(term) }
    else { text.split_whitespace().any(|word| word == term) }
}

/// Classifies a bounded request and returns matched evidence for diagnostics.
pub fn classify(input: &str) -> IntentResult {
    if input.trim().is_empty() || input.len() > 16_384 {
        return IntentResult { intent: Intent::General, score: 0, matched_terms: vec![], approval_required: false };
    }
    let text = normalize(input);
    let mut selected = Intent::General;
    let mut high_score = 0;
    let mut evidence = Vec::new();
    for rule in RULES {
        let found: Vec<_> = rule.terms.iter().copied().filter(|term| matches_term(&text, term)).collect();
        let score = found.len() as u32 * rule.weight;
        if score > high_score {
            selected = rule.intent;
            high_score = score;
            evidence = found;
        }
    }
    IntentResult { intent: selected, score: high_score, matched_terms: evidence, approval_required: selected.requires_approval() }
}

pub fn classify_label(input: &str) -> &'static str { classify(input).intent.label() }

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn detects_build() { assert_eq!(classify("compile APK for release").intent, Intent::Build); }
    #[test] fn actions_require_approval() { assert!(classify("swipe down and open app").approval_required); }
    #[test] fn detects_code() { assert_eq!(classify("write Kotlin code").intent, Intent::CodeGeneration); }
    #[test] fn workspace_is_protected() { assert!(classify("delete file draft.txt").approval_required); }
    #[test] fn handles_punctuation() { assert_eq!(classify("BUILD-APK!!!").intent, Intent::Build); }
    #[test] fn bounds_input() { assert_eq!(classify(&"x".repeat(20_000)).intent, Intent::General); }
    #[test] fn empty_is_safe() { assert_eq!(classify("").intent, Intent::General); }
    #[test] fn unknown_is_general() { assert_eq!(classify("hello friend").intent, Intent::General); }
}
